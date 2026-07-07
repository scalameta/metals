package scala.meta.internal.metals.debug

import java.time.Duration

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.debug.BuildTargetClasses.Classes
import scala.meta.internal.metals.debug.BuildTargetClasses.TestSymbolInfo
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.mbt.MbtWorkspaceSymbolProvider
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.Scope
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.ValueSignature
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.io.AbsolutePath

import bloop.config.Config.TestFramework
import ch.epfl.scala.{bsp4j => b}

/**
 * In-memory index of main class symbols grouped by their enclosing build target
 */
final class BuildTargetClasses(
    val buildTargets: BuildTargets,
    val compilers: () => Compilers,
    symbolIndex: OnDemandSymbolIndex,
    mbt: () => Option[MbtWorkspaceSymbolProvider] = () => None,
    workDoneProgress: BaseWorkDoneProgress = EmptyWorkDoneProgress,
)(implicit
    val ec: ExecutionContext
) extends SemanticdbFeatureProvider {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

  private val bazelTestClassCache =
    TrieMap.empty[AbsolutePath, List[(String, TestSymbolInfo)]]

  private val symbolCache = new SymbolCache(compilers, symbolIndex)

  private val MbtSemanticdbBatchSize = 5

  type JVMRunEnvironmentsMap =
    TrieMap[b.BuildTargetIdentifier, b.JvmEnvironmentItem]

  /** Cache for the 'buildTarget/jvmRunEnvironment' BSP requests */
  private val jvmRunEnvironments: JVMRunEnvironmentsMap = TrieMap.empty

  /** Cache for the 'buildTarget/jvmTestRunEnvironment' BSP requests */
  private val jvmTestRunEnvironments: JVMRunEnvironmentsMap = TrieMap.empty

  def jvmRunEnvironmentsFor(isTests: Boolean): JVMRunEnvironmentsMap =
    if (isTests) jvmTestRunEnvironments else jvmRunEnvironments

  val rebuildIndex: BatchedFunction[b.BuildTargetIdentifier, Unit] =
    BatchedFunction.fromFuture(
      fetchClasses,
      "buildTargetClasses",
      default = Some(()),
    )

  override def onChange(docs: TextDocuments, path: AbsolutePath): Unit = {
    onChangeAsync(docs, path).ignoreValue
  }

  def onChangeAsync(docs: TextDocuments, path: AbsolutePath): Future[Unit] = {
    if (
      path.isScalaOrJava && hasBazelBuildServer && belongsToTestTarget(path)
    ) {
      symbolCache.removeSymbolsForPath(path)
      extractTestClassesFromDocuments(docs, path).map { testClasses =>
        cacheBazelTestClasses(path, testClasses)
      }
    } else Future.unit
  }

  override def onDelete(path: AbsolutePath): Unit = {
    bazelTestClassCache.remove(path)
  }
  override def reset(): Unit = {
    bazelTestClassCache.clear()
    symbolCache.clear()
  }

  def classesOf(target: b.BuildTargetIdentifier): Classes = {
    index.getOrElse(target, new Classes)
  }

  def invalidate(target: b.BuildTargetIdentifier): Unit = {
    index.put(target, new Classes)
  }

  def clear(): Unit = {
    jvmRunEnvironments.clear()
    jvmTestRunEnvironments.clear()
  }

  def findMainClassByName(
      name: String
  ): List[(b.ScalaMainClass, b.BuildTargetIdentifier)] =
    findClassesBy(_.mainClasses.values.find(_.getClassName() == name))

  def findTestClassByName(
      name: String
  ): List[(String, b.BuildTargetIdentifier)] =
    findClassesBy(
      _.testClasses.values
        .find(_.fullyQualifiedName == name)
        .map(_.fullyQualifiedName)
    )

  def sourceFileForMbtTestClass(
      className: String,
      targetId: b.BuildTargetIdentifier,
  ): Option[AbsolutePath] =
    index.get(targetId).flatMap(_.confirmedMbtTestClassFile.get(className))

  def resolveCandidateTestClass(
      name: String,
      target: Option[String],
  ): Future[Unit] = {
    // Find targets that have candidates that might match this class name
    val normalizedClassName = name.replace(".", "/")
    val targetId =
      target.flatMap(buildTargets.findByDisplayNameOrUri(_).map(_.getId))
    val requestedTargets = targetId
      .map(targetId => index.get(targetId).toList.map(targetId -> _))
      .getOrElse(index.toList)
    val targetsWithMatchingCandidates = requestedTargets.flatMap {
      case (targetId, classes) =>
        val hasMatchingCandidate = classes.candidateTestClasses.values.flatten
          .exists(_.candidateSymbol.contains(normalizedClassName))
        if (hasMatchingCandidate) Some(targetId) else None
    }

    if (targetsWithMatchingCandidates.isEmpty) {
      Future.unit
    } else {
      confirmMbtTestClassCandidatesForTargets(targetsWithMatchingCandidates)
    }

  }

  def getTestClasses(
      name: String,
      id: b.BuildTargetIdentifier,
  ): List[(String, TestSymbolInfo)] = {
    index.get(id).toList.flatMap { classes =>
      scribe.debug(
        s"""|Found test classes:
            |${classes.testClasses.values.map(info => s"\t- ${info.fullyQualifiedName}").mkString("\n")}""".stripMargin
      )
      classes.testClasses.filter { case (_, info) =>
        info.fullyQualifiedName == name
      }.toList
    }
  }

  private def findClassesBy[A](
      f: Classes => Option[A]
  ): List[(A, b.BuildTargetIdentifier)] = {
    index.view
      .mapValues(f)
      .toList
      .collect { case (target, Some(clazz)) =>
        clazz -> target
      }
  }

  private def fetchClasses(
      targets: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    val distinctTargets = targets.distinct
    Future
      .traverse(distinctTargets.groupBy(buildTargets.buildServerOf).toSeq) {
        case (None, _) =>
          Future.successful(())
        case (Some(connection), targets0) =>
          val targetsList = targets0.asJava
          val classes = targets0.map(t => (t, new Classes)).toMap

          val updateMainClasses = connection
            .mainClasses(new b.ScalaMainClassesParams(targetsList))
            .map(cacheMainClasses(classes, _))

          val updateTestClasses =
            if (hasBazelBuildServer) {
              Future.successful(
                populateBazelTestClasses(classes, targets0)
              )
            } else {
              connection
                .testClasses(new b.ScalaTestClassesParams(targetsList))
                .map(cacheTestClasses(classes, _))
            }

          val populateMbtClasses =
            if (MbtBuildServer.isMbtServer(connection.name)) {
              populateMbtMainClasses(classes, targets0).flatMap { _ =>
                populateMbtTestClasses(classes, targets0)
              }
            } else Future.unit

          for {
            _ <- updateMainClasses
            _ <- updateTestClasses
            _ <- workDoneProgress.trackFuture(
              "Discovering main classes and tests",
              populateMbtClasses,
            )
          } yield {
            targetsList.forEach(invalidate)
            classes.foreach { case (id, classes) =>
              index.put(id, classes)
            }
          }
      }
      .ignoreValue
  }

  def jvmRunEnvironmentSync(
      buildTargetId: b.BuildTargetIdentifier,
      isTests: Boolean,
  ): Option[b.JvmEnvironmentItem] =
    jvmRunEnvironmentsFor(isTests).get(buildTargetId)

  def jvmRunEnvironment(
      buildTargetId: b.BuildTargetIdentifier,
      isTests: Boolean,
  ): Future[Option[b.JvmEnvironmentItem]] = {
    val environments = jvmRunEnvironmentsFor(isTests)

    environments.get(buildTargetId) match {
      case None =>
        scribe.trace(
          s"No cached JVM run environment for build target $buildTargetId (isTests = $isTests)."
        )

        buildTargets.buildServerOf(buildTargetId) match {
          case None =>
            scribe.trace(
              s"No build server connection found for build target $buildTargetId (isTests = $isTests)."
            )
            Future.successful(None)

          case Some(connection) =>
            scribe.trace(
              s"Found build server connection for build target $buildTargetId (isTests = $isTests)."
            )
            val buildTargets = List(buildTargetId)

            def processResult(items: Iterable[b.JvmEnvironmentItem]) = {
              cacheJvmRunEnvironment(items.iterator, environments)
              items.headOption
            }

            if (isTests) {
              connection
                .jvmTestEnvironment(
                  new b.JvmTestEnvironmentParams(buildTargets.asJava)
                )
                .map(env => processResult(env.getItems().asScala))
            } else {
              connection
                .jvmRunEnvironment(
                  new b.JvmRunEnvironmentParams(buildTargets.asJava)
                )
                .map(env => processResult(env.getItems().asScala))
            }

        }

      case jvmRunEnv: Some[b.JvmEnvironmentItem] =>
        scribe.trace(
          s"Found cached JVM run environment for build target $buildTargetId (isTests = $isTests)."
        )
        Future.successful(jvmRunEnv)
    }
  }

  private def cacheJvmRunEnvironment(
      items: Iterator[b.JvmEnvironmentItem],
      environments: JVMRunEnvironmentsMap,
  ): Unit = {
    for {
      item <- items
      target = item.getTarget
    } {
      environments.put(target, item)
    }
  }

  private def cacheMainClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaMainClassesResult,
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      aClass <- item.getClasses.asScala
      descriptors = descriptorsForMainClasses(target)
      symbol <- symbolFromClassName(
        aClass.getClassName,
        descriptors,
      )
    } {
      classes(target).mainClasses.put(symbol, aClass)
    }
  }

  private def cacheTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      result: b.ScalaTestClassesResult,
  ): Unit = {
    for {
      item <- result.getItems.asScala
      target = item.getTarget
      className <- item.getClasses.asScala
      symbol <-
        symbolFromClassName(
          className,
          List(Descriptor.Term.apply, Descriptor.Type.apply),
        )
    } {
      // item.getFramework() can return null!
      val framework = TestFrameworkUtils.from(Option(item.getFramework()))
      val testInfo = BuildTargetClasses.TestSymbolInfo(className, framework)
      classes(target).testClasses.put(symbol, testInfo)
    }
  }

  private def descriptorsForMainClasses(
      buildTarget: b.BuildTargetIdentifier
  ): List[String => Descriptor] = {
    buildTargets.scalaTarget(buildTarget) match {
      case Some(_) =>
        List(Descriptor.Term.apply)
      case None =>
        List(Descriptor.Type.apply)
    }
  }

  def symbolFromClassName(
      className: String,
      descriptors: List[String => Descriptor],
  ): List[String] = {
    import scala.reflect.NameTransformer
    val isEmptyPackage = !className.contains(".")
    val root =
      if (isEmptyPackage) Symbols.EmptyPackage
      else Symbols.RootPackage
    val names = className.stripSuffix("$").split("\\.")
    val prefix = names.dropRight(1).foldLeft(root) { (owner, name) =>
      Symbols.Global(owner, Descriptor.Package(NameTransformer.decode(name)))
    }
    val name = NameTransformer.decode(names.last)
    descriptors.map(descriptor => Symbols.Global(prefix, descriptor(name)))
  }

  def cancel(): Unit = {
    rebuildIndex.cancelAll()
  }

  private def hasBazelBuildServer: Boolean = {
    buildTargets.allBuildTargetIds.exists { targetId =>
      buildTargets.buildServerOf(targetId).exists(_.isBazel)
    }
  }

  private def belongsToTestTarget(path: AbsolutePath): Boolean = {
    buildTargets.inverseSources(path).exists { targetId =>
      buildTargets.info(targetId).exists { buildTarget =>
        buildTarget.getTags.asScala.contains("test")
      }
    }
  }

  private def extractTestClassesFromDocuments(
      docs: TextDocuments,
      path: AbsolutePath,
  ): Future[List[(String, TestSymbolInfo)]] =
    Future
      .sequence(docs.documents.map(extractTestClassesFromDocument(_, path)))
      .map(_.flatten.toList)

  private def extractTestClassesFromDocument(
      doc: TextDocument,
      path: AbsolutePath,
  ): Future[List[(String, TestSymbolInfo)]] = {
    val testClasses =
      scala.collection.mutable.ListBuffer[(String, TestSymbolInfo)]()

    val futures = doc.symbols.flatMap { symbolInfo =>
      processTestAnnotations(symbolInfo, testClasses)
      // Only Scala files depend on inheritance for test frameworks
      if (path.isJava)
        None
      else
        processTestClassHierarchy(symbolInfo, doc, path, testClasses)
    }

    Future.sequence(futures).map(_ => testClasses.toList)
  }

  private def processTestAnnotations(
      symbolInfo: SymbolInformation,
      testClasses: scala.collection.mutable.ListBuffer[(String, TestSymbolInfo)],
  ): Unit = {
    symbolInfo.annotations.foreach { annotation =>
      annotation.tpe match {
        case TypeRef(_, annotationSymbol, _) =>
          TestFrameworkDetector.fromSymbol(annotationSymbol) match {
            case Some(framework) =>
              val classSymbol = symbolInfo.symbol
              val className = symbolToClassName(classSymbol)
              val testInfo = TestSymbolInfo(className, framework)
              val classSymbolWithoutFunctionName =
                classSymbol.indexOf('#') match {
                  case -1 => classSymbol
                  case index => classSymbol.substring(0, index + 1)
                }
              testClasses += ((classSymbolWithoutFunctionName, testInfo))
            case None =>
          }
        case _ =>
      }
    }
  }

  private def processTestClassHierarchy(
      symbolInfo: SymbolInformation,
      doc: TextDocument,
      path: AbsolutePath,
      testClasses: scala.collection.mutable.ListBuffer[(String, TestSymbolInfo)],
  ): Option[Future[Unit]] = {
    symbolInfo.signature match {
      case classSig: ClassSignature =>
        val symbol = symbolInfo.symbol
        val className = symbolToClassName(symbol)
        if (className.nonEmpty) {
          Some(
            detectTestFrameworkUsingClassHierarchy(classSig, doc, path)
              .map { frameworkOpt =>
                frameworkOpt.foreach { framework =>
                  val testInfo = TestSymbolInfo(className, framework)
                  testClasses += ((symbol, testInfo))
                }
              }
          )
        } else None
      case _ => None
    }
  }

  private def detectTestFrameworkUsingClassHierarchy(
      classSig: ClassSignature,
      doc: TextDocument,
      path: AbsolutePath,
  ): Future[Option[TestFramework]] = {
    val initialParents = extractParentSymbols(classSig)
    searchClassHierarchyForTestFramework(
      initialParents,
      doc,
      path,
    )
  }

  private def searchClassHierarchyForTestFramework(
      symbols: List[String],
      doc: TextDocument,
      path: AbsolutePath,
      visited: Set[String] = Set.empty,
  ): Future[Option[TestFramework]] = {
    if (symbols.isEmpty) {
      Future.successful(None)
    } else {
      val directFramework = symbols
        .map(symbol =>
          TestFrameworkDetector
            .fromSymbol(symbol)
        )
        .collectFirst { case Some(framework) => framework }

      directFramework match {
        case Some(framework) => Future.successful(Some(framework))
        case None =>
          val nextLevelFutures = symbols
            .filterNot(visited.contains)
            .map(symbol => collectParentsForSymbol(symbol, doc, path, visited))
          Future.sequence(nextLevelFutures).flatMap { parentList =>
            val allParents = parentList.flatten.distinct

            searchClassHierarchyForTestFramework(
              allParents,
              doc,
              path,
              visited ++ symbols,
            )
          }
      }
    }
  }

  private def extractParentSymbols(classSig: ClassSignature): List[String] = {
    classSig.parents.collect { case TypeRef(_, parentSymbol, _) =>
      parentSymbol
    }.toList
  }

  private def collectParentsForSymbol(
      symbol: String,
      doc: TextDocument,
      path: AbsolutePath,
      visited: Set[String],
  ): Future[List[String]] = {
    if (visited.contains(symbol)) {
      Future.successful(Nil)
    } else {
      doc.symbols.find(_.symbol == symbol) match {
        case Some(symbolInfo) =>
          symbolInfo.signature match {
            case classSig: ClassSignature =>
              Future.successful(extractParentSymbols(classSig))
            case _ => Future.successful(Nil)
          }
        case None =>
          symbolCache.getCachedSymbolInfo(path, symbol).map {
            case Some(pcInfo) => pcInfo.recursiveParents
            case None => Nil
          }
      }
    }
  }

  private def symbolToClassName(symbol: String, suffix: Char = '#'): String = {
    val withoutPrefix = symbol.stripPrefix("_empty_/")
    val withoutHashAndAfter = withoutPrefix.indexOf(suffix) match {
      case -1 => withoutPrefix
      case index => withoutPrefix.substring(0, index)
    }
    withoutHashAndAfter.replace("/", ".")
  }

  private def populateBazelTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      targets: Seq[b.BuildTargetIdentifier],
  ): Unit = {
    import scala.jdk.CollectionConverters.*

    targets.foreach { target =>
      val sourceFiles = buildTargets.sourceItemsToBuildTargets
        .filter { case (_, buildTargetIds) =>
          buildTargetIds.asScala.toList.contains(target)
        }
        .map(_._1)
        .filter(_.isScalaOrJava)
        .toList

      sourceFiles.foreach { sourcePath =>
        bazelTestClassCache.get(sourcePath).foreach { testClasses =>
          testClasses.foreach { case (symbol, testInfo) =>
            classes(target).testClasses.put(symbol, testInfo)
          }
        }
      }
    }
  }

  private def cacheBazelTestClasses(
      path: AbsolutePath,
      testClasses: List[(String, TestSymbolInfo)],
  ): Unit = {
    if (testClasses.nonEmpty) {
      bazelTestClassCache.put(path, testClasses)
      val targetIds = buildTargets.inverseSourcesAll(path)
      for {
        targetId <- targetIds
        buildTarget <- buildTargets.info(targetId)
        if buildTarget.getTags.asScala.contains("test")
      } {
        val classes = index.getOrElseUpdate(targetId, new Classes)
        testClasses.foreach { case (symbol, testInfo) =>
          classes.testClasses.put(symbol, testInfo)
        }
      }
    }
  }

  /**
   * Populates candidate test classes from the MBT index WITHOUT loading semanticdb.
   * The candidates are stored in `candidateTestClasses` and will be confirmed
   * lazily when:
   * - A file is opened (for code lenses or test explorer)
   * - User explicitly requests test discovery (test explorer opened)
   *
   * This optimization avoids the expensive semanticdb loading at startup.
   */
  private def populateMbtTestClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      targets: Seq[b.BuildTargetIdentifier],
  ): Future[Unit] = Future {
    mbt() match {
      case None =>
        scribe.warn("mbt-test: MBT workspace symbol provider is not available.")
      case Some(mbtProvider) =>
        val targetSet = targets.toSet

        val candidates = mbtProvider.candidateTestClasses(
          filterPath = path =>
            path.isScalaOrJava &&
              buildTargets.inverseSourcesAll(path).exists(targetSet),
          annotationSymbols = TestFrameworkSymbolRegistry.annotationSymbols,
          baseParentSymbols = TestFrameworkSymbolRegistry.baseParentSymbols,
        )

        // Group candidates by path
        val candidatesByPath = candidates
          .groupBy(_.path)
          .view
          .mapValues(
            _.map(c =>
              BuildTargetClasses.TestClassCandidate(c.path, c.candidateSymbol)
            )
          )
          .toMap

        // Store candidates in the appropriate target classes
        for {
          (path, pathCandidates) <- candidatesByPath
          targetId <- buildTargets.inverseSourcesAll(path).filter(targetSet)
        } {
          val existingCandidates =
            classes(targetId).candidateTestClasses.getOrElse(path, Seq.empty)
          classes(targetId).candidateTestClasses
            .put(path, existingCandidates ++ pathCandidates)
        }

        scribe.debug(
          s"mbt-test: stored ${candidates.size} test class candidates from ${candidatesByPath.size} files"
        )
    }
  }

  /**
   * Populates candidate main classes from the MBT index WITHOUT loading semanticdb.
   * The candidates are stored in `candidateMainClasses` and will be confirmed
   * lazily when:
   * - A file is opened (for code lenses)
   * - User runs test from config and uses discovery
   *
   * This optimization avoids the expensive semanticdb loading at startup.
   */
  private def populateMbtMainClasses(
      classes: Map[b.BuildTargetIdentifier, Classes],
      targets: Seq[b.BuildTargetIdentifier],
  ): Future[Unit] = Future {
    mbt() match {
      case None =>
        scribe.warn("mbt-run: MBT workspace symbol provider is not available.")
      case Some(mbtProvider) =>
        val targetSet = targets.toSet

        val candidates = mbtProvider.candidateMainClasses(path =>
          path.isScalaOrJava &&
            buildTargets.inverseSourcesAll(path).exists(targetSet)
        )
        // Group candidates by path
        val candidatesByPath = candidates
          .groupBy(_.path)
          .view
          .mapValues(
            _.map(c =>
              BuildTargetClasses.MainClassCandidate(c.path, c.candidateSymbol)
            )
          )
          .toMap

        // Store candidates in the appropriate target classes
        for {
          (path, pathCandidates) <- candidatesByPath
          targetId <- buildTargets.inverseSourcesAll(path).filter(targetSet)
        } {
          val existingCandidates =
            classes(targetId).candidateMainClasses.getOrElse(path, Seq.empty)
          classes(targetId).candidateMainClasses
            .put(path, existingCandidates ++ pathCandidates)
        }

        scribe.debug(
          s"mbt-run: stored ${candidates.size} main class candidates from ${candidatesByPath.size} files"
        )
    }
  }

  /**
   * Confirms candidate main classes for a specific file by loading semanticdb.
   * This should be called when:
   * - A file is opened (for code lenses)
   * - User runs test from config and uses discovery
   *
   * @param path The file path to confirm candidates for
   * @param targetId Optional target to limit confirmation to
   * @return Future that completes when confirmation is done
   */
  def confirmMbtMainClassCandidates(
      path: AbsolutePath,
      targetId: b.BuildTargetIdentifier,
  ): Future[Unit] = {
    val targetIds = Seq(targetId)

    val hasCandidates = targetIds.exists { tid =>
      index.get(tid).exists(_.candidateMainClasses.contains(path))
    }

    if (!hasCandidates) {
      Future.unit
    } else {
      foreachMbtSemanticdbDocument(Seq(path)) { doc =>
        Future {
          processMbtSemanticdb(doc.uri.toAbsolutePath, doc, targetIds)
        }
      }
    }
  }

  private def foreachMbtSemanticdbDocument(
      candidatePaths: Seq[AbsolutePath]
  )(f: TextDocument => Future[Unit]): Future[Unit] = {
    candidatePaths.grouped(MbtSemanticdbBatchSize).foldLeft(Future.unit) {
      case (previous, batch) =>
        previous.flatMap { _ =>
          compilers()
            .batchSemanticdbTextDocuments(
              batch,
              EmptyCancelToken,
              Duration.ofSeconds(120),
              useFallbackCompiler = true,
              shouldPruneSemanticdb = true,
            )
            .recover { case e =>
              scribe.error(s"Error parsing semanticdb text documents: $e", e)
              TextDocuments(documents = Seq.empty)
            }
            .flatMap { documents =>
              Future.sequence(documents.documents.map(f)).map(_ => ())
            }
        }
    }
  }

  private def processMbtSemanticdb(
      docPath: AbsolutePath,
      doc: TextDocument,
      targetIds: Seq[b.BuildTargetIdentifier],
  ): Unit = {
    for {
      tid <- targetIds
      classes <- index.get(tid)
    } {
      // Remove candidates for this path
      classes.candidateMainClasses.remove(docPath)

      // Extract and store confirmed main classes
      val mainClasses = extractMbtMainClasses(doc)
      for ((symbol, mc) <- mainClasses) {
        classes.mainClasses.put(symbol, mc)
      }
    }
  }

  /**
   * Confirms candidate main classes for specific targets, optionally filtering by class name.
   * This is more targeted than confirming all candidates and avoids unnecessary semanticdb loading.
   *
   * @param targetIds The build targets to confirm candidates for
   * @param mainClassName Optional main class name filter. If provided, only candidates that
   *                      might match this class name will be confirmed.
   * @return Future that completes when confirmation is done
   */
  def confirmMbtMainClassCandidatesForTargets(
      targetIds: Seq[b.BuildTargetIdentifier],
      mainClassName: Option[String] = None,
  ): Future[Unit] = {
    // Collect candidate paths from the specified targets
    val candidatePaths = for {
      tid <- targetIds
      classes <- index.get(tid).toSeq
      (path, candidates) <- classes.candidateMainClasses.toSeq
      // If a main class name is provided, filter candidates that might match
      if mainClassName.forall { className =>
        val normalizedClassName = className.replace(".", "/")
        candidates.exists { candidate =>
          candidate.candidateSymbol.contains(normalizedClassName)
        }
      }
    } yield path
    val distinctPaths = candidatePaths.distinct

    if (distinctPaths.isEmpty) {
      Future.unit
    } else {
      foreachMbtSemanticdbDocument(distinctPaths) { doc =>
        Future {
          val docPath = doc.uri.toAbsolutePath
          val docTargetIds = buildTargets.inverseSourcesAll(docPath)
          processMbtSemanticdb(
            docPath,
            doc,
            docTargetIds.filter(targetIds.contains),
          )
        }
      }
    }
  }

  /**
   * Confirms candidate test classes for a specific file by loading semanticdb.
   * This should be called when:
   * - A file is opened (for code lenses or test explorer)
   * - User explicitly requests test discovery
   *
   * @param path The file path to confirm candidates for
   * @param targetId The target to limit confirmation to
   * @return Future that completes when confirmation is done
   */
  def confirmMbtTestClassCandidates(
      path: AbsolutePath,
      targetId: b.BuildTargetIdentifier,
  ): Future[Unit] = {
    val targetIds = Seq(targetId)

    val hasCandidates = targetIds.exists { tid =>
      index.get(tid).exists(_.candidateTestClasses.contains(path))
    }
    if (!hasCandidates) {
      Future.unit
    } else {
      foreachMbtSemanticdbDocument(Seq(path)) { doc =>
        processMbtTestSemanticdb(doc.uri.toAbsolutePath, doc, targetIds)
      }
    }
  }

  /**
   * Confirms candidate test classes for specific targets.
   * This is useful when the user opens the test explorer and wants to discover all tests.
   *
   * @param targetIds The build targets to confirm candidates for
   * @return Future that completes when confirmation is done
   */
  def confirmMbtTestClassCandidatesForTargets(
      targetIds: Seq[b.BuildTargetIdentifier]
  ): Future[Unit] = {
    // Collect candidate paths from the specified targets
    val candidatePaths = for {
      tid <- targetIds
      classes <- index.get(tid).toSeq
      (path, _) <- classes.candidateTestClasses.toSeq
    } yield path
    val distinctPaths = candidatePaths.distinct

    if (distinctPaths.isEmpty) {
      Future.unit
    } else {
      foreachMbtSemanticdbDocument(distinctPaths) { doc =>
        val docPath = doc.uri.toAbsolutePath
        val docTargetIds = buildTargets.inverseSourcesAll(docPath)
        processMbtTestSemanticdb(
          docPath,
          doc,
          docTargetIds.filter(targetIds.contains),
        )
      }
    }
  }

  /**
   * Processes semanticdb to extract and store confirmed test classes.
   * Also removes candidates for the processed path.
   */
  private def processMbtTestSemanticdb(
      docPath: AbsolutePath,
      doc: TextDocument,
      targetIds: Seq[b.BuildTargetIdentifier],
  ): Future[Unit] = {
    extractTestClassesFromDocument(doc, docPath).map { testClasses =>
      for {
        tid <- targetIds
        classes <- index.get(tid)
      } {
        // Remove candidates for this path
        classes.candidateTestClasses.remove(docPath)

        // Extract and store confirmed test classes
        for ((symbol, testInfo) <- testClasses) {
          classes.testClasses.put(symbol, testInfo)
          classes.confirmedMbtTestClassFile.put(
            testInfo.fullyQualifiedName,
            docPath,
          )
        }
      }
    }
  }

  private def extractMbtMainClasses(
      textDocument: TextDocument
  ): List[(String, b.ScalaMainClass)] = {
    val result = mutable.LinkedHashMap.empty[String, b.ScalaMainClass]
    textDocument.symbols.foreach { info =>
      val symbol = info.symbol
      if (isJavaMainMethod(info, textDocument)) {
        val classSymbol = symbol.stripSuffix("#main().")
        result.put(symbol, mbtMainClass(classSymbol))
      } else if (isScalaMainMethod(info, textDocument)) {
        val classSymbol = symbol.stripSuffix(".main().") + "."
        val className = symbolToClassName(classSymbol, '.')
        result.put(classSymbol, mbtMainClass(className))
      } else if (isScalaApp(info)) {
        val className = symbolToClassName(symbol, '.')
        result.put(symbol, mbtMainClass(className))
      } else {
        mainFromScalaMainAnnotation(info).foreach { mainSymbol =>
          result.put(mainSymbol, mbtMainClass(mainSymbol))
        }
      }
    }
    result.toList
  }

  private def isJavaMainMethod(
      info: SymbolInformation,
      textDocument: TextDocument,
  ): Boolean =
    info.symbol.endsWith("#main().") &&
      info.isPublic &&
      info.isStatic &&
      isMainMethodSignature(info.signature, textDocument)

  private def isScalaMainMethod(
      info: SymbolInformation,
      textDocument: TextDocument,
  ): Boolean =
    info.symbol.endsWith(".main().") &&
      isMainMethodSignature(info.signature, textDocument)

  private def isMainMethodSignature(
      signature: Signature,
      textDocument: TextDocument,
  ): Boolean =
    signature match {
      case MethodSignature(_, Seq(Scope(Seq(param), _)), returnType, _) =>
        def isUnit = returnType match {
          case TypeRef(_, symbol, _) => symbol == "scala/Unit#"
          case _ => false
        }
        def isStringArray(sym: String) =
          textDocument.symbols.find(_.symbol == sym).exists {
            _.signature match {
              case ValueSignature(
                    TypeRef(
                      _,
                      "scala/Array#",
                      Vector(TypeRef(_, str, _)),
                    )
                  )
                  if str == "scala/Predef.String#" || str == "java/lang/String#" =>
                true
              case _ => false
            }
          }
        isUnit && isStringArray(param)
      case _ => false
    }

  private def isScalaApp(info: SymbolInformation): Boolean =
    (info.kind.isClass || info.kind.isObject) &&
      (info.signature match {
        case ClassSignature(_, parents, _, _) =>
          parents.exists {
            case TypeRef(_, "scala/App#", _) => true
            case _ => false
          }
        case _ => false
      })

  private def mainFromScalaMainAnnotation(
      info: SymbolInformation
  ): Option[String] = {
    val hasMainAnnotation = info.annotations.exists {
      _.tpe match {
        case TypeRef(_, "scala/main#", _) => true
        case _ => false
      }
    }
    Option
      .when(hasMainAnnotation)(info.symbol)
      .flatMap(DebugDiscovery.mainSymbolFromScala3MainMethod)
  }

  private def mbtMainClass(symbol: String): b.ScalaMainClass =
    new b.ScalaMainClass(symbolToClassName(symbol), Nil.asJava, Nil.asJava)
}

object TestFrameworkDetector {
  def fromSymbol(symbol: String): Option[TestFramework] = {
    TestFrameworkSymbolRegistry.frameworkForSymbol(symbol)
  }
}

object TestFrameworkSymbolRegistry {
  import scala.meta.internal.metals.testProvider.frameworks.*

  private lazy val scalatestSymbols: Map[String, TestFramework] =
    ScalatestStyle.baseSymbols.map(_ -> TestFramework.ScalaTest).toMap

  private lazy val munitSymbols: Map[String, TestFramework] =
    MunitTestFinder.baseParentClasses.map(_ -> TestFramework.munit).toMap

  private lazy val weaverSymbols: Map[String, TestFramework] =
    WeaverCatsEffectTestFinder.baseParentClasses
      .map(_ -> TestFrameworkUtils.WeaverTestFramework)
      .toMap

  private lazy val zioTestSymbols: Map[String, TestFramework] =
    ZioTestFinder.baseParentClasses
      .map(_ -> TestFrameworkUtils.ZioTestFramework)
      .toMap

  private lazy val junitSymbols: Map[String, TestFramework] =
    JunitTestFinder.junitAnnotationSymbols.map(_ -> TestFramework.JUnit).toMap

  private lazy val testngSymbols: Map[String, TestFramework] = {
    val testngFinder = new TestNGTestFinder()
    Set(testngFinder.expectedAnnotationSymbol)
      .map(_ -> TestFramework.TestNG)
      .toMap
  }

  private lazy val allFrameworkSymbols: Map[String, TestFramework] =
    scalatestSymbols ++
      munitSymbols ++
      weaverSymbols ++
      zioTestSymbols ++
      junitSymbols ++
      testngSymbols

  def frameworkForSymbol(symbol: String): Option[TestFramework] = {
    allFrameworkSymbols.get(symbol)
  }

  /** Annotation symbols used by JUnit and TestNG test discovery. */
  def annotationSymbols: Seq[String] =
    (junitSymbols.keys ++ testngSymbols.keys).toSeq.distinct

  /** Base class symbols used by ScalaTest, MUnit, Weaver, and ZIO Test discovery. */
  def baseParentSymbols: Seq[String] =
    (scalatestSymbols.keys ++ munitSymbols.keys ++ weaverSymbols.keys ++ zioTestSymbols.keys).toSeq.distinct
}

object TestFrameworkUtils {
  val WeaverTestFramework: TestFramework = TestFramework(
    List("weaver.framework.CatsEffect")
  )

  val ZioTestFramework: TestFramework = TestFramework(
    List("zio.test.sbt.ZTestFramework")
  )

  private lazy val supportedFrameworks = Set(
    TestFramework.JUnit,
    TestFramework.munit,
    TestFramework.ScalaTest,
    WeaverTestFramework,
    TestFramework.TestNG,
    ZioTestFramework,
  )

  def from(framework: Option[String]): TestFramework = framework
    .map {
      case "JUnit" => TestFramework.JUnit
      case "munit" => TestFramework.munit
      case "ScalaTest" => TestFramework.ScalaTest
      case "weaver-cats-effect" => WeaverTestFramework
      case "TestNG" => TestFramework.TestNG
      case x if x.contains("ZIO Test") => ZioTestFramework
      case _ => TestFramework(Nil)
    }
    .getOrElse(TestFramework(Nil))

  def canResolveTests(framework: TestFramework): Boolean = supportedFrameworks(
    framework
  )
}

object BuildTargetClasses {
  type Symbol = String
  type FullyQualifiedClassName = String

  final case class TestSymbolInfo(
      fullyQualifiedName: FullyQualifiedClassName,
      framework: TestFramework,
  )

  /**
   * Represents a candidate main class that has been discovered from the MBT index
   * without loading semanticdb. The candidate needs to be confirmed before being
   * added to mainClasses.
   */
  final case class MainClassCandidate(
      path: AbsolutePath,
      candidateSymbol: String,
  )

  /**
   * Represents a candidate test class that has been discovered from the MBT index
   * without loading semanticdb. The candidate needs to be confirmed before being
   * added to testClasses.
   */
  final case class TestClassCandidate(
      path: AbsolutePath,
      candidateSymbol: String,
  )

  final class Classes {
    val mainClasses = new TrieMap[Symbol, b.ScalaMainClass]()
    val testClasses = new TrieMap[Symbol, TestSymbolInfo]()

    /**
     * Maps fully-qualified test class name to its source file, populated when
     * candidates are confirmed via semanticdb. Persists after confirmation so
     * that source-file lookups remain valid.
     */
    val confirmedMbtTestClassFile =
      new TrieMap[FullyQualifiedClassName, AbsolutePath]()

    /**
     * Candidate main classes discovered from the MBT index without semanticdb.
     * These are unconfirmed and need semanticdb verification before use.
     * Key is the file path, value is the list of candidate symbols in that file.
     */
    val candidateMainClasses =
      new TrieMap[AbsolutePath, Seq[MainClassCandidate]]()

    /**
     * Candidate test classes discovered from the MBT index without semanticdb.
     * These are unconfirmed and need semanticdb verification before use.
     * Key is the file path, value is the list of candidate symbols in that file.
     */
    val candidateTestClasses =
      new TrieMap[AbsolutePath, Seq[TestClassCandidate]]()

    def isEmpty: Boolean = mainClasses.isEmpty && testClasses.isEmpty
  }
}
