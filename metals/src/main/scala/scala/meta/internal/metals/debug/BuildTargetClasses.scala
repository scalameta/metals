package scala.meta.internal.metals.debug

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SemanticdbFeatureProvider
import scala.meta.internal.metals.debug.BuildTargetClasses.Classes
import scala.meta.internal.metals.debug.BuildTargetClasses.TestSymbolInfo
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TypeRef
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
)(implicit
    val ec: ExecutionContext
) extends SemanticdbFeatureProvider {
  private val index = TrieMap.empty[b.BuildTargetIdentifier, Classes]

  private val bazelTestClassCache =
    TrieMap.empty[AbsolutePath, List[(String, TestSymbolInfo)]]

  private val symbolCache = new SymbolCache(compilers, symbolIndex)

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
    if (
      path.isScalaFilename && hasBazelBuildServer && belongsToTestTarget(path)
    ) {
//      invalidateTestFrameworkCacheForDocuments(docs)
      symbolCache.removeSymbolsForPath(path)
      extractTestClassesFromDocuments(docs, path).foreach { testClasses =>
        if (testClasses.nonEmpty) {
          bazelTestClassCache.put(path, testClasses)
        }
      }
    }
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

          for {
            _ <- updateMainClasses
            _ <- updateTestClasses
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
  ): Future[List[(String, TestSymbolInfo)]] = {
    val testClasses =
      scala.collection.mutable.ListBuffer[(String, TestSymbolInfo)]()

    val futures = docs.documents.flatMap { doc =>
      doc.symbols.flatMap { symbolInfo =>
        processTestAnnotations(symbolInfo, testClasses)
        processTestClassHierarchy(symbolInfo, doc, path, testClasses)
      }
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

  private def symbolToClassName(symbol: String): String = {
    val withoutPrefix = symbol.stripPrefix("_empty_/")
    val withoutHashAndAfter = withoutPrefix.indexOf('#') match {
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
        .filter(_.isScalaFilename)
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

}

object TestFrameworkDetector {
  def fromSymbol(symbol: String): Option[TestFramework] = {
    TestFrameworkSymbolRegistry.frameworkForSymbol(symbol)
  }
}

object TestFrameworkSymbolRegistry {
  import scala.meta.internal.metals.testProvider.frameworks.ScalatestStyle
  import scala.meta.internal.metals.testProvider.frameworks.MunitTestFinder
  import scala.meta.internal.metals.testProvider.frameworks.WeaverCatsEffectTestFinder
  import scala.meta.internal.metals.testProvider.frameworks.ZioTestFinder
  import scala.meta.internal.metals.testProvider.frameworks.JunitTestFinder
  import scala.meta.internal.metals.testProvider.frameworks.TestNGTestFinder

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

  private lazy val junitSymbols: Map[String, TestFramework] = Set(
    JunitTestFinder.junitBaseClassSymbol,
    JunitTestFinder.junitAnnotationSymbol,
  ).map(_ -> TestFramework.JUnit).toMap

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

  final class Classes {
    val mainClasses = new TrieMap[Symbol, b.ScalaMainClass]()
    val testClasses = new TrieMap[Symbol, TestSymbolInfo]()

    def isEmpty: Boolean = mainClasses.isEmpty && testClasses.isEmpty
  }
}
