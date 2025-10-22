package scala.meta.internal.metals

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.{util => ju}

import scala.build.bsp.WrappedSourceItem
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.inputs.Input
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.builds.WorkspaceReload
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.IndexingResult
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonObject
import org.eclipse.lsp4j.Position

// todo https://github.com/scalameta/metals/issues/4788
// clean () =>, use plain values

/**
 * Coordinates build target data fetching and caching, and the re-computation of various
 * indexes based on it.
 */
case class Indexer(indexProviders: IndexProviders)(implicit rc: ReportContext) {
  import indexProviders._

  private implicit def ec: ExecutionContextExecutorService = executionContext
  val sharedIndices: SqlSharedIndices = new SqlSharedIndices

  var bspSession: Option[BspSession] = Option.empty[BspSession]

  protected val workspaceReload: WorkspaceReload = new WorkspaceReload(
    folder,
    languageClient,
    tables,
  )

  def index(check: () => Unit): Future[Unit] = profiledIndexWorkspace(check)

  protected def profiledIndexWorkspace(check: () => Unit): Future[Unit] = {
    val tracked = workDoneProgress.trackFuture(
      Messages.indexing,
      Future {
        timerProvider.timedThunk("indexed workspace", onlyIf = true) {
          try indexWorkspace(check)
          finally {
            indexingPromise.trySuccess(())
          }
        }
      },
    )
    tracked.foreach { _ =>
      statusBar.addMessage(
        s"${clientConfig.icons().rocket} Indexing complete!"
      )
      if (clientConfig.initialConfig.statistics.isMemory) {
        Memory.logMemory(
          List(
            ("definition index", definitionIndex),
            ("references index", referencesProvider.index),
            ("identifier index", referencesProvider.identifierIndex.index),
            ("workspace symbol index", workspaceSymbols.inWorkspace),
            ("build targets", buildTargets),
            (
              "classpath symbol index",
              workspaceSymbols.inDependencies.packages,
            ),
          )
        )
      }
    }
    tracked
  }

  private def indexWorkspace(check: () => Unit): Unit = {
    fileWatcher.cancel()

    timerProvider.timedThunk(
      "reset stuff",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      resetService()
    }
    val allBuildTargetsData = buildData()
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        s"updated ${buildTool.name} build targets",
        clientConfig.initialConfig.statistics.isIndex,
      ) {
        val data = buildTool.data
        val importedBuild = buildTool.importedBuild
        data.reset()
        buildTargetClasses.clear()
        data.addWorkspaceBuildTargets(importedBuild.workspaceBuildTargets)
        fileChanges.addAllDirty(
          importedBuild.workspaceBuildTargets
            .getTargets()
            .map(_.getId())
            .asScala
        )
        data.addScalacOptions(
          importedBuild.scalacOptions,
          bspSession.map(_.mainConnection),
        )
        data.addJavacOptions(
          importedBuild.javacOptions,
          bspSession.map(_.mainConnection),
        )

        data.addDependencyModules(
          importedBuild.dependencyModules
        )

        // For "wrapped sources", we create dedicated TargetData.MappedSource instances,
        // able to convert back and forth positions from the user-facing file to
        // the compiler-facing actual underlying source.
        for {
          item <- importedBuild.wrappedSources.getItems.asScala
          sourceItem <- item.getSources.asScala
        } {
          val path = sourceItem.getUri.toAbsolutePath
          val mappedSource =
            if (path.isScalaScript) createMappedSourceForScript(sourceItem)
            else simpleMappedSource(sourceItem)
          data.addMappedSource(path, mappedSource)
        }
        for {
          item <- importedBuild.sources.getItems.asScala
          source <- item.getSources.asScala
        } {
          data.addSourceItem(source, item.getTarget)
        }
      }
    timerProvider.timedThunk(
      "post update build targets stuff",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      check()
    }
    timerProvider.timedThunk(
      "started file watcher",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      try {
        fileWatcher.cancel()
        fileWatcher.start()
      } catch {
        // note(@tgodzik) This is needed in case of ammonite
        // where it can rarely deletes directories while we are trying to watch them
        case NonFatal(e) =>
          fileWatcher.cancel()
          scribe.warn("File watching failed, indexes will not be updated.", e)
      }
    }
    timerProvider.timedThunk(
      "indexed library classpath",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      workspaceSymbols.indexClasspath()
    }
    timerProvider.timedThunk(
      "indexed workspace SemanticDBs",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      semanticDBIndexer.onTargetRoots()
    }
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        s"indexed workspace ${buildTool.name} sources",
        clientConfig.initialConfig.statistics.isIndex,
      ) {
        indexWorkspaceSources(buildTool.data)
      }
    var usedJars = Set.empty[AbsolutePath]
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        "indexed library sources",
        clientConfig.initialConfig.statistics.isIndex,
      ) {
        usedJars ++= indexJdkSources(
          buildTool.data,
          buildTool.importedBuild.dependencySources,
        )
        usedJars ++= indexDependencyModules(
          buildTool.data,
          buildTool.importedBuild.dependencyModules,
        )
        usedJars ++= indexDependencySources(
          buildTool.data,
          buildTool.importedBuild.dependencySources,
        )
      }
    // Schedule removal of unused toplevel symbols from cache
    if (usedJars.nonEmpty)
      sh.schedule(
        new Runnable {
          override def run(): Unit = {
            if (tables.databaseExists())
              tables.jarSymbols.deleteNotUsedTopLevels(usedJars.toArray)
          }
        },
        2,
        TimeUnit.SECONDS,
      )

    focusedDocument.foreach { doc =>
      buildTargets
        .inverseSources(doc)
        .foreach(focusedDocumentBuildTarget.set)
    }

    val targets = buildTargets.allBuildTargetIds
    buildTargetClasses
      .rebuildIndex(targets)
      .foreach { _ =>
        languageClient.refreshModel()
      }
  }

  /*
    sourceItem tells us how the user-facing sources (typically, an *.sc file)
    gets wrapped (to a .scala file), so that scalac can compile it fine.

    sourceItem.getTopWrapper needs to be added before the content of the user-facing
    code, and sourceItem.getBottomWrapper needs to be added after it.

    In the case of Scala CLI, these typically look like, for a file named foo/hello.sc:

        package foo
        object hello {

    at the top, and at the bottom:

          def args = hello_sc.args$
        }
        object hello_sc {
          private var args$opt = Option.empty[Array[String]]
          def args$ = args$opt.getOrElse(sys.error("No arguments passed to this script"))
          def main(args: Array[String]): Unit = {
            args$opt = Some(args)
            hello
          }
        }

      Here, we see that this puts the file in a package named 'foo', and this adds a method
      called 'args', that users can call from their code (alongside with an object hello_sc
      with a main class, that doesn't matter much from Metals here).

      The toScala and fromScala methods below adjust positions, because of the code
      added at the top that shifts lines.

      mappedSource allows to wrap things on-the-fly, to pass not-yet-saved code to
      the interactive compiler.
   */
  private def createMappedSourceForScript(sourceItem: WrappedSourceItem) = {
    val generatedPath = sourceItem.getGeneratedUri.toAbsolutePath
    val topWrapperLineCount = sourceItem.getTopWrapper.count(_ == '\n')
    val toScala: Position => Position =
      scPos =>
        new Position(
          topWrapperLineCount + scPos.getLine,
          scPos.getCharacter,
        )
    val fromScala: Position => Position =
      scalaPos =>
        new Position(
          scalaPos.getLine - topWrapperLineCount,
          scalaPos.getCharacter,
        )

    new TargetData.MappedSource {
      def path = generatedPath
      def update(
          content: String
      ): (Input.VirtualFile, Position => Position, AdjustLspData) = {
        val adjustLspData = AdjustedLspData.create(fromScala)
        val updatedContent =
          sourceItem.getTopWrapper + content + sourceItem.getBottomWrapper
        (
          Input.VirtualFile(
            generatedPath.toNIO.toString
              .stripSuffix(".scala") + ".sc.scala",
            updatedContent,
          ),
          toScala,
          adjustLspData,
        )
      }
      override def lineForServer(line: Int): Option[Int] =
        Some(line + topWrapperLineCount)
      override def lineForClient(line: Int): Option[Int] =
        Some(line - topWrapperLineCount)
    }
  }

  def simpleMappedSource(
      sourceItem: WrappedSourceItem
  ): TargetData.MappedSource = {
    val generatedPath = sourceItem.getGeneratedUri.toAbsolutePath

    new TargetData.MappedSource {
      def path = generatedPath
      def update(
          content: String
      ): (Input.VirtualFile, Position => Position, AdjustLspData) = {
        val actualContent = generatedPath.readTextOpt.getOrElse(content)
        (
          Input.VirtualFile(generatedPath.toString(), actualContent),
          identity,
          AdjustedLspData.default,
        )
      }
      override def lineForServer(line: Int): Option[Int] = Some(line)
      override def lineForClient(line: Int): Option[Int] = Some(line)
    }
  }

  def indexWorkspaceSources(data: Seq[TargetData]): Unit = {
    for (data0 <- data.iterator)
      indexWorkspaceSources(data0)
  }
  def indexWorkspaceSources(data: TargetData): Unit = {
    case class SourceToIndex(
        source: AbsolutePath,
        sourceItem: AbsolutePath,
        targets: Iterable[b.BuildTargetIdentifier],
    )
    val sourcesToIndex = mutable.ListBuffer.empty[SourceToIndex]
    for {
      (sourceItem, targets) <- data.sourceItemsToBuildTarget
      source <- sourceItem.listRecursive
      if source.isScalaOrJava
    } {
      targets.asScala.foreach { target =>
        data.linkSourceFile(target, source)
      }
      sourcesToIndex += SourceToIndex(source, sourceItem, targets.asScala)
    }
    val threadPool = new ForkJoinPool(
      Runtime.getRuntime().availableProcessors() match {
        case 1 => 1
        case f => f / 2
      }
    )
    try {
      val parSourcesToIndex = sourcesToIndex.toSeq.par
      parSourcesToIndex.tasksupport = new ForkJoinTaskSupport(threadPool)
      parSourcesToIndex.foreach(f =>
        indexSourceFile(
          f.source,
          Some(f.sourceItem),
          f.targets.headOption,
          Seq(data),
        )
      )
    } finally threadPool.shutdown()
  }

  private def processDependencyPath(
      path: AbsolutePath,
      target: b.BuildTargetIdentifier,
      usedJars: mutable.HashSet[AbsolutePath],
  ): Unit = {
    try {
      if (path.isJar && path.exists) {
        usedJars += path
        addSourceJarSymbols(path)
      } else if (path.isDirectory) {
        val dialect = buildTargets
          .scalaTarget(target)
          .map(scalaTarget =>
            ScalaVersions.dialectForScalaVersion(
              scalaTarget.scalaVersion,
              includeSource3 = true,
            )
          )
          .getOrElse(Scala213)
        definitionIndex.addSourceDirectory(path, dialect)
      } else {
        scribe.warn(s"unexpected dependency with absolute path: $path")
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(s"Error processing $path", e)
    }
  }

  private def indexDependencyModules(
      data: TargetData,
      dependencyModules: b.DependencyModulesResult,
  ): Set[AbsolutePath] = {
    val usedJars = mutable.HashSet.empty[AbsolutePath]
    val isVisited = new ju.HashSet[String]()

    for {
      item <- dependencyModules.getItems.asScala
      module <- item.getModules.asScala
      if module.getData != null
      uri <- module.getData match {
        case jsonObject: JsonObject =>
          Option(jsonObject.get("artifacts")) match {
            case Some(artifactsElement) if artifactsElement.isJsonArray =>
              try {
                artifactsElement.getAsJsonArray.asScala
                  .filter(element =>
                    element.isJsonObject &&
                      element.getAsJsonObject.has("classifier")
                  )
                  .map(_.getAsJsonObject.get("uri").getAsString)
                  .toList
              } catch {
                case NonFatal(e) =>
                  scribe.warn(
                    s"Error processing artifacts array: ${e.getMessage}"
                  )
                  Nil
              }
            case _ => Nil
          }
        case _ => Nil
      }
      absolutePath = uri.toAbsolutePath
      _ = data.addDependencySource(absolutePath, item.getTarget)
      if !isVisited.contains(uri)
    } {
      isVisited.add(uri)
      processDependencyPath(absolutePath, item.getTarget, usedJars)
    }

    usedJars.toSet
  }

  private def indexDependencySources(
      data: TargetData,
      dependencySources: b.DependencySourcesResult,
  ): Set[AbsolutePath] = {
    // Track used Jars so that we can
    // remove cached symbols from Jars
    // that are not used
    val usedJars = mutable.HashSet.empty[AbsolutePath]
    val isVisited = new ju.HashSet[String]()
    for {
      item <- dependencySources.getItems.asScala
      sourceUri <- Option(item.getSources).toList.flatMap(_.asScala)
      path = sourceUri.toAbsolutePath
      _ = data.addDependencySource(path, item.getTarget)
      if !isVisited.contains(sourceUri)
    } {
      isVisited.add(sourceUri)
      processDependencyPath(path, item.getTarget, usedJars)
    }
    usedJars.toSet
  }

  private def indexJdkSources(
      data: TargetData,
      dependencySources: b.DependencySourcesResult,
  ): Set[AbsolutePath] = {
    // Track used Jars so that we can
    // remove cached symbols from Jars
    // that are not used
    val usedJars = mutable.HashSet.empty[AbsolutePath]
    val jdkSources = JdkSources(userConfig.javaHome)
    jdkSources match {
      case Right(zip) =>
        scribe.debug(s"Indexing JDK sources from $zip")
        usedJars += zip
        val dialect = ScalaVersions.dialectForDependencyJar(zip, buildTargets)
        sharedIndices.jvmTypeHierarchy.getTypeHierarchy(zip) match {
          case Some(overrides) =>
            definitionIndex.addIndexedSourceJar(zip, Nil, dialect)
            implementationProvider.addTypeHierarchyElements(overrides)
          case None =>
            val (_, overrides, _, _) = indexJar(zip, dialect)
            sharedIndices.jvmTypeHierarchy.addTypeHierarchyInfo(
              zip,
              overrides,
            )
        }
      case Left(notFound) =>
        val candidates = notFound.candidates.mkString(", ")
        scribe.warn(
          s"Could not find java sources in $candidates. Java symbols will not be available."
        )
    }
    for {
      item <- dependencySources.getItems.asScala
    } {
      jdkSources.foreach { source =>
        data.addDependencySource(source, item.getTarget)
      }
    }
    usedJars.toSet
  }

  private def indexSourceFile(
      source: AbsolutePath,
      sourceItem: Option[AbsolutePath],
      targetOpt: Option[b.BuildTargetIdentifier],
      data: Seq[TargetData],
  ): Unit = {
    try {
      val sourceToIndex0 =
        sourceMapper.mappedTo(source).getOrElse(source)
      if (sourceToIndex0.exists) {
        val dialect = {
          val scalaVersion =
            targetOpt
              .flatMap(id =>
                data.iterator
                  .flatMap(_.buildTargetInfo.get(id).iterator)
                  .find(_ => true)
                  .flatMap(_.asScalaBuildTarget)
              )
              .map(_.getScalaVersion())
              .getOrElse(
                scalaVersionSelector.fallbackScalaVersion()
              )
          ScalaVersions.dialectForScalaVersion(
            scalaVersion,
            includeSource3 = true,
          )
        }
        val reluri = source.toIdeallyRelativeURI(sourceItem)
        val input = sourceToIndex0.toInput
        val symbols = ArrayBuffer.empty[WorkspaceSymbolInformation]
        val methodSymbols = ArrayBuffer.empty[WorkspaceSymbolInformation]
        val optMtags = SemanticdbDefinition.foreachWithReturnMtags(
          input,
          dialect,
          includeMembers = true,
          collectIdentifiers = true,
        ) { case SemanticdbDefinition(info, occ, owner) =>
          if (info.isExtension) {
            occ.range.foreach { range =>
              methodSymbols += WorkspaceSymbolInformation(
                info.symbol,
                info.kind,
                range.toLsp,
              )
            }
          } else {
            if (info.kind.isRelevantKind) {
              occ.range.foreach { range =>
                symbols += WorkspaceSymbolInformation(
                  info.symbol,
                  info.kind,
                  range.toLsp,
                )
              }
            }
          }
          if (
            sourceItem.isDefined &&
            !info.symbol.isPackage &&
            (owner.isPackage || source.isScalaScript)
          ) {
            definitionIndex.addToplevelSymbol(
              reluri,
              source,
              info.symbol,
              dialect,
            )
          }
        }
        optMtags
          .map(_.allIdentifiers)
          .filter(_.nonEmpty)
          .foreach(identifiers =>
            referencesProvider.addIdentifiers(source, identifiers)
          )
        
        // Extract implicit class members from the indexing
        optMtags.foreach { mtags =>
          val implicitMembers = mtags.implicitClassMembers()
          if (implicitMembers.nonEmpty) {
            scribe.info(
              s"[Indexer.indexSourceFile] Found ${implicitMembers.size} implicit class members in $source"
            )
            workspaceSymbols.addImplicitClassMembers(Map(source -> implicitMembers))
          }
        }
        
        workspaceSymbols.didChange(source, symbols.toSeq, methodSymbols.toSeq)

        // Since the `symbols` here are toplevel symbols,
        // we cannot use `symbols` for expiring the cache for all symbols in the source.
        symbolDocs.expireSymbolDefinition(sourceToIndex0, dialect)
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(source.toString(), e)
    }
  }

  /**
   * Add top level Scala symbols from source JAR into symbol index
   * Uses H2 cache for symbols
   *
   * @param path JAR path
   */
  private def addSourceJarSymbols(path: AbsolutePath): Unit = {
    val dialect = ScalaVersions.dialectForDependencyJar(path, buildTargets)
    tables.jarSymbols.getTopLevels(path) match {
      case Some(toplevels) =>
        tables.jarSymbols.getTypeHierarchy(path) match {
          case Some(overrides) =>
            definitionIndex.addIndexedSourceJar(path, toplevels, dialect)
            implementationProvider.addTypeHierarchyElements(overrides)
            tables.jarSymbols.getToplevelMembers(path) match {
              case Some(toplevelMembersMap) =>
                workspaceSymbols.addToplevelMembers(toplevelMembersMap)
              case None =>
                val (_, _, toplevelMembers, implicitClassMembers) =
                  indexJar(path, dialect, reindex = true)
                if (toplevelMembers.nonEmpty) {
                  tables.jarSymbols.addToplevelMembersInfo(
                    path,
                    toplevelMembers,
                  )
                }
                if (implicitClassMembers.nonEmpty) {
                  tables.jarSymbols.addImplicitClassMembersInfo(
                    path,
                    implicitClassMembers,
                  )
                }
            }
            tables.jarSymbols.getImplicitClassMembers(path) match {
              case Some(implicitClassMembersMap) =>
                workspaceSymbols.addImplicitClassMembers(implicitClassMembersMap)
              case None => // already handled above if we had to reindex
            }
          case None =>
            val (_, overrides, toplevelMembers, implicitClassMembers) =
              indexJar(path, dialect, reindex = true)
            tables.jarSymbols.addTypeHierarchyInfo(path, overrides)
            if (toplevelMembers.nonEmpty) {
              tables.jarSymbols.addToplevelMembersInfo(path, toplevelMembers)
            }
            if (implicitClassMembers.nonEmpty) {
              tables.jarSymbols.addImplicitClassMembersInfo(path, implicitClassMembers)
            }
        }
      case None =>
        val (toplevels, overrides, toplevelMembers, implicitClassMembers) = indexJar(path, dialect)
        tables.jarSymbols.putJarIndexingInfo(
          path,
          toplevels,
          overrides,
          toplevelMembers,
          implicitClassMembers,
        )
    }
  }

  private def indexJar(
      path: AbsolutePath,
      dialect: Dialect,
      reindex: Boolean = false,
  ) = {
    val indexResult = definitionIndex.addSourceJar(path, dialect, reindex)
    val toplevels = indexResult.flatMap {
      case IndexingResult(path, toplevels, _, _, _) =>
        toplevels.map((_, path))
    }
    val overrides = indexResult.flatMap {
      case IndexingResult(path, _, list, _, _) =>
        list.flatMap { case (symbol, overridden) =>
          overridden.map((path, symbol, _))
        }
    }
    val toplevelMembersMap = indexResult.collect {
      case IndexingResult(path, _, _, toplevelMembers, _)
          if toplevelMembers.nonEmpty =>
        path -> toplevelMembers
    }.toMap
    val implicitClassMembersMap = indexResult.collect {
      case IndexingResult(path, _, _, _, implicitClassMembers)
          if implicitClassMembers.nonEmpty =>
        scribe.info(
          s"[Indexer.indexJar] Found ${implicitClassMembers.size} implicit class members in $path"
        )
        path -> implicitClassMembers
    }.toMap
    scribe.info(
      s"[Indexer.indexJar] Total implicit class members map size: ${implicitClassMembersMap.size} files"
    )
    implementationProvider.addTypeHierarchyElements(overrides)
    workspaceSymbols.addToplevelMembers(toplevelMembersMap)
    workspaceSymbols.addImplicitClassMembers(implicitClassMembersMap)
    scribe.info(
      s"[Indexer.indexJar] Added implicit class members to workspace symbols"
    )
    (toplevels, overrides, toplevelMembersMap, implicitClassMembersMap)
  }

  def reindexWorkspaceSources(
      paths: Seq[AbsolutePath]
  ): Unit = {
    val data = buildData().map(_.data)
    for {
      path <- paths.iterator
      if path.isScalaOrJava
    } {
      indexSourceFile(path, buildTargets.inverseSourceItem(path), None, data)
    }
  }
}

object Indexer {
  final case class BuildTool(
      name: String,
      data: TargetData,
      importedBuild: ImportedBuild,
  )
}
