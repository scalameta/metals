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
import scala.meta.internal.metals.mbt.OnDidChangeSymbolsParams
import scala.meta.internal.mtags.DependencyModule
import scala.meta.internal.mtags.IndexingResult
import scala.meta.internal.mtags.MavenCoordinates
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.Position
import org.eclipse.{lsp4j => l}

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
    () => userConfig.buildChangedAction,
  )

  def index(check: () => Unit, progress: TaskProgress): Future[Unit] =
    profiledIndexWorkspace(check, progress)

  protected def profiledIndexWorkspace(
      check: () => Unit,
      progress: TaskProgress,
  ): Future[Unit] = {
    val tracked = Future {
      timerProvider.timedThunk(
        "indexed workspace",
        onlyIf = true,
        metricName = Some("index_workspace"),
      ) {
        try indexWorkspace(check, progress)
        finally {
          indexingPromise.trySuccess(())
        }
      }
    }
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

  private def indexWorkspace(
      check: () => Unit,
      progress: TaskProgress,
  ): Unit = {
    fileWatcher.cancel()

    timerProvider.timedThunk(
      "reset stuff",
      clientConfig.initialConfig.statistics.isIndex,
      metricName = Some("index_workspace_reset_stuff"),
    ) {
      progress.message = "indexing setup"
      resetService()
    }
    val allBuildTargetsData = buildData()
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        s"updated ${buildTool.name} build targets",
        clientConfig.initialConfig.statistics.isIndex,
        metricName = Some("index_workspace_update_build_targets"),
      ) {
        progress.message = "indexing build targets"
        val data = buildTool.data
        val importedBuild = buildTool.importedBuild
        data.reset()
        buildTargetClasses.clear()
        data.addWorkspaceBuildTargets(importedBuild.workspaceBuildTargets)
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
      metricName = Some("index_workspace_post_update_build_targets"),
    ) {
      progress.message = "analyzing build targets"
      check()
    }
    timerProvider.timedThunk(
      "started file watcher",
      clientConfig.initialConfig.statistics.isIndex,
      metricName = Some("index_workspace_file_watcher"),
    ) {
      progress.message = "creating file watchers"
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
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        s"indexed workspace ${buildTool.name} sources",
        clientConfig.initialConfig.statistics.isIndex,
        metricName = Some("index_workspace_sources"),
      ) {
        progress.message = "indexing sources"
        indexWorkspaceSources(buildTool.data, progress)
      }
    timerProvider.timedThunk(
      "indexed library classpath",
      clientConfig.initialConfig.statistics.isIndex,
      metricName = Some("index_workspace_classpath"),
    ) {
      progress.message = "indexing classpath"
      workspaceSymbols.indexClasspath(progress)
    }
    timerProvider.timedThunk(
      "indexed workspace SemanticDBs",
      clientConfig.initialConfig.statistics.isIndex,
      metricName = Some("index_workspace_semanticdb"),
    ) {
      progress.message = "indexing semanticdbs"
      semanticDBIndexer.onTargetRoots()
    }
    var usedJars = Set.empty[AbsolutePath]
    for (buildTool <- allBuildTargetsData)
      timerProvider.timedThunk(
        s"indexed library sources for ${buildTool.name}",
        clientConfig.initialConfig.statistics.isIndex,
        metricName = Some("index_library_sources"),
      ) {
        progress.message = "indexing JDK sources"
        usedJars ++= indexJdkSources(
          buildTool.data,
          buildTool.importedBuild.dependencySources,
        )
        progress.message =
          s"indexing ${buildTool.importedBuild.dependencyModules.getItems().size()} dependencies"
        if (indexProviders.userConfig.definitionIndexStrategy.isClasspath) {
          indexDependencyModules(
            buildTool.importedBuild.dependencyModules,
            progress,
          )
        }
        usedJars ++= indexDependencySources(
          buildTool.data,
          buildTool.importedBuild.dependencySources,
          progress,
        )
        scribe.debug(s"indexed ${usedJars.size} dependency source jars")
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

    progress.message = "updating focused documents"
    focusedDocument.foreach { doc =>
      buildTargets
        .inverseSources(doc)
        .foreach(focusedDocumentBuildTarget.set)
    }

    progress.message = "updating classes"
    val targets = buildTargets.allBuildTargetIds
    buildTargetClasses
      .rebuildIndex(targets)
      .foreach { _ =>
        languageClient.refreshModel()
      }
    progress.message = ""
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

  def indexWorkspaceSources(
      data: Seq[TargetData],
      progress: TaskProgress = TaskProgress.empty,
  ): Unit = {
    for (data0 <- data.iterator)
      indexWorkspaceSources(data0, progress)
  }
  def indexWorkspaceSources(data: TargetData, progress: TaskProgress): Unit = {
    case class SourceToIndex(
        source: AbsolutePath,
        sourceItem: AbsolutePath,
        targets: Iterable[b.BuildTargetIdentifier],
    )
    val sourcesToIndex = mutable.ArrayBuffer.empty[SourceToIndex]
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
    // at this point we have all sources for each build target, so presentation compilers should
    // restart to pick up the correct sourcepath. We don't need to wait for the actual
    // indexing to finish since they don't impact how the presentation compiler works
    resetPresentationCompilers()

    val threadPool = new ForkJoinPool(
      Runtime.getRuntime().availableProcessors() match {
        case 1 => 1
        case f => f / 2
      }
    )
    try {
      val parSourcesToIndex = sourcesToIndex.toSeq.par
      progress.message = s"indexing ${parSourcesToIndex.length} sources"
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

  private def indexDependencyModules(
      dependencyModules: b.DependencyModulesResult,
      progress: TaskProgress,
  ): Unit = {
    val isVisited = new ju.HashSet[AbsolutePath]()
    for {
      item <- dependencyModules.getItems.asScala
      module <- Option(item.getModules()).toList.flatMap(_.asScala.iterator)
      maven <- module.asMavenDependencyModule
      jar <- maven.getArtifacts().asScala.collectFirst {
        case a if a.getClassifier() == null => a.getUri().asURItoAbsolutePath
      }
      if jar.isJar && !isVisited.contains(jar)
    } {
      progress.progress = progress.progress + 1
      isVisited.add(jar)
      val sources = maven.getArtifacts().asScala.collectFirst {
        case a if a.getClassifier() == "sources" =>
          a.getUri().asURItoAbsolutePath
      }
      val coordinates = MavenCoordinates(
        maven.getOrganization(),
        maven.getName(),
        maven.getVersion(),
      )
      val dependencyModule = DependencyModule(coordinates, jar, sources)
      val dialect = buildTargets
        .scalaTarget(item.getTarget)
        .map(scalaTarget =>
          ScalaVersions.dialectForScalaVersion(
            scalaTarget.scalaVersion,
            includeSource3 = true,
          )
        )
        .getOrElse(Scala213)
      definitionIndex.addDependencyModule(dependencyModule, dialect)
    }
  }

  private def indexDependencySources(
      data: TargetData,
      dependencySources: b.DependencySourcesResult,
      progress: TaskProgress,
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
      progress.progress = progress.progress + 1
      isVisited.add(sourceUri)
      try {
        if (path.isJar && path.exists) {
          if (!indexProviders.userConfig.definitionIndexStrategy.isClasspath) {
            usedJars += path
            addSourceJarSymbols(path)
          }
        } else if (path.isDirectory) {
          val dialect = buildTargets
            .scalaTarget(item.getTarget)
            .map(scalaTarget =>
              ScalaVersions.dialectForScalaVersion(
                scalaTarget.scalaVersion,
                includeSource3 = true,
              )
            )
            .getOrElse(Scala213)

          definitionIndex.addSourceDirectory(path, dialect)
        } else {
          scribe.warn(s"unexpected dependency: $path")
        }
      } catch {
        case NonFatal(e) =>
          scribe.error(s"error processing $sourceUri", e)
      }
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
            val (_, overrides) = indexJar(zip, dialect)
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
                scalaVersionSelector.fallbackScalaVersion(
                  source.isAmmoniteScript
                )
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
        val allSymbols = ArrayBuffer.empty[WorkspaceSymbolInformation]
        val optMtags = SemanticdbDefinition.foreachWithReturnMtags(
          indexProviders.mtags,
          input,
          dialect,
          includeMembers = true,
          collectIdentifiers = true,
        ) { case SemanticdbDefinition(info, occ, owner) =>
          allSymbols += WorkspaceSymbolInformation(
            info.symbol,
            info.kind,
            occ.range.fold(
              new l.Range(new l.Position(0, 0), new l.Position(0, 0))
            )(_.toLsp),
          )
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
            (owner.isPackage || source.isAmmoniteScript)
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
        workspaceSymbols.didChange(source, symbols.toSeq, methodSymbols.toSeq)
        mbtSymbolSearch.onDidChangeSymbols(
          OnDidChangeSymbolsParams(
            source,
            input,
            allSymbols,
            methodSymbols.toSeq,
          )
        )

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
          case None =>
            scribe.debug(s"Indexing source jar $path")
            val (_, overrides) = indexJar(path, dialect)
            tables.jarSymbols.addTypeHierarchyInfo(path, overrides)
        }
      case None =>
        scribe.debug(s"Indexing source jar $path")
        val (toplevels, overrides) = indexJar(path, dialect)
        tables.jarSymbols.putJarIndexingInfo(path, toplevels, overrides)
    }
  }

  private def indexJar(path: AbsolutePath, dialect: Dialect) = {
    val indexResult = definitionIndex.addSourceJar(path, dialect)
    val toplevels = indexResult.flatMap {
      case IndexingResult(path, toplevels, _) =>
        toplevels.map((_, path))
    }
    val overrides = indexResult.flatMap { case IndexingResult(path, _, list) =>
      list.flatMap { case (symbol, overridden) =>
        overridden.map((path, symbol, _))
      }
    }
    implementationProvider.addTypeHierarchyElements(overrides)
    (toplevels, overrides)
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
