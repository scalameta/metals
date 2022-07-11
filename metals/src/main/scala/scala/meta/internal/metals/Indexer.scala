package scala.meta.internal.metals

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.CollectionConverters._
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.dialects._
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.builds.WorkspaceReload
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.DelegatingLanguageClient
import scala.meta.internal.metals.clients.language.ForwardingMetalsBuildClient
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.tvp.TreeViewProvider
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}

/**
 * Coordinates build target data fetching and caching, and the re-computation of various
 * indexes based on it.
 */
final case class Indexer(
    workspaceReload: () => WorkspaceReload,
    doctor: () => Doctor,
    languageClient: DelegatingLanguageClient,
    bspSession: () => Option[BspSession],
    executionContext: ExecutionContextExecutorService,
    tables: () => Tables,
    statusBar: () => StatusBar,
    timerProvider: TimerProvider,
    scalafixProvider: () => ScalafixProvider,
    indexingPromise: Promise[Unit],
    buildData: () => Seq[Indexer.BuildTool],
    clientConfig: ClientConfiguration,
    definitionIndex: OnDemandSymbolIndex,
    referencesProvider: () => ReferenceProvider,
    workspaceSymbols: () => WorkspaceSymbolProvider,
    buildTargets: BuildTargets,
    interactiveSemanticdbs: () => InteractiveSemanticdbs,
    buildClient: () => ForwardingMetalsBuildClient,
    semanticDBIndexer: () => SemanticdbIndexer,
    treeView: () => TreeViewProvider,
    worksheetProvider: () => WorksheetProvider,
    symbolSearch: () => MetalsSymbolSearch,
    buildTools: () => BuildTools,
    formattingProvider: () => FormattingProvider,
    fileWatcher: FileWatcher,
    focusedDocument: () => Option[AbsolutePath],
    focusedDocumentBuildTarget: AtomicReference[b.BuildTargetIdentifier],
    buildTargetClasses: BuildTargetClasses,
    userConfig: () => UserConfiguration,
    sh: ScheduledExecutorService,
    symbolDocs: Docstrings,
    scalaVersionSelector: ScalaVersionSelector,
    sourceMapper: SourceMapper,
) {

  private implicit def ec: ExecutionContextExecutorService = executionContext

  def reloadWorkspaceAndIndex(
      forceRefresh: Boolean,
      buildTool: BuildTool,
      checksum: String,
  ): Future[BuildChange] = {
    def reloadAndIndex(session: BspSession): Future[BuildChange] = {
      workspaceReload().persistChecksumStatus(Status.Started, buildTool)

      session
        .workspaceReload()
        .map { _ =>
          scribe.info("Correctly reloaded workspace")
          profiledIndexWorkspace(() => doctor().check())
          workspaceReload().persistChecksumStatus(Status.Installed, buildTool)
          BuildChange.Reloaded
        }
        .recoverWith { case NonFatal(e) =>
          scribe.error(s"Unable to reload workspace: ${e.getMessage()}")
          workspaceReload().persistChecksumStatus(Status.Failed, buildTool)
          languageClient.showMessage(Messages.ReloadProjectFailed)
          Future.successful(BuildChange.Failed)
        }
    }

    bspSession() match {
      case None =>
        scribe.warn("No build session currently active to reload.")
        Future.successful(BuildChange.None)
      case Some(session) if forceRefresh => reloadAndIndex(session)
      case Some(session) =>
        workspaceReload().oldReloadResult(checksum) match {
          case Some(status) =>
            scribe.info(s"Skipping reload with status '${status.name}'")
            Future.successful(BuildChange.None)
          case None =>
            for {
              userResponse <- workspaceReload().requestReload(
                buildTool,
                checksum,
              )
              installResult <- {
                if (userResponse.isYes) {
                  reloadAndIndex(session)
                } else {
                  tables().dismissedNotifications.ImportChanges
                    .dismiss(2, TimeUnit.MINUTES)
                  Future.successful(BuildChange.None)
                }
              }
            } yield installResult
        }
    }
  }

  def profiledIndexWorkspace(check: () => Unit): Future[Unit] = {
    val tracked = statusBar().trackFuture(
      s"Indexing",
      Future {
        timerProvider.timedThunk("indexed workspace", onlyIf = true) {
          try indexWorkspace(check)
          finally {
            Future(scalafixProvider().load())
            indexingPromise.trySuccess(())
          }
        }
      },
    )
    tracked.foreach { _ =>
      statusBar().addMessage(
        s"${clientConfig.icons.rocket} Indexing complete!"
      )
      if (clientConfig.initialConfig.statistics.isMemory) {
        Memory.logMemory(
          List(
            ("definition index", definitionIndex),
            ("references index", referencesProvider().index),
            ("workspace symbol index", workspaceSymbols().inWorkspace),
            ("build targets", buildTargets),
            (
              "classpath symbol index",
              workspaceSymbols().inDependencies.packages,
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
      interactiveSemanticdbs().reset()
      buildClient().reset()
      semanticDBIndexer().reset()
      treeView().reset()
      worksheetProvider().reset()
      symbolSearch().reset()
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
        data.addWorkspaceBuildTargets(importedBuild.workspaceBuildTargets)
        data.addScalacOptions(importedBuild.scalacOptions)
        data.addJavacOptions(importedBuild.javacOptions)
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
      buildTools()
        .loadSupported()
      formattingProvider().validateWorkspace()
    }
    timerProvider.timedThunk(
      "started file watcher",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      try {
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
      workspaceSymbols().indexClasspath()
    }
    timerProvider.timedThunk(
      "indexed workspace SemanticDBs",
      clientConfig.initialConfig.statistics.isIndex,
    ) {
      semanticDBIndexer().onTargetRoots()
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
            tables().jarSymbols.deleteNotUsedTopLevels(usedJars.toArray)
          }
        },
        2,
        TimeUnit.SECONDS,
      )

    focusedDocument().foreach { doc =>
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
      try {
        if (path.isJar) {
          usedJars += path
          addSourceJarSymbols(path)
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
    val jdkSources = JdkSources(userConfig().javaHome)
    jdkSources match {
      case Right(zip) =>
        usedJars += zip
        addSourceJarSymbols(zip)
      case Left(notFound) =>
        val candidates = notFound.candidates.mkString(", ")
        scribe.warn(
          s"Could not find java sources in $candidates. Java symbols will not be available."
        )
    }
    for {
      item <- dependencySources.getItems.asScala
    } {
      jdkSources.foreach(source =>
        data.addDependencySource(source, item.getTarget)
      )
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
        SemanticdbDefinition.foreach(input, dialect) {
          case SemanticdbDefinition(info, occ, owner) =>
            if (WorkspaceSymbolProvider.isRelevantKind(info.kind)) {
              occ.range.foreach { range =>
                symbols += WorkspaceSymbolInformation(
                  info.symbol,
                  info.kind,
                  range.toLSP,
                )
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
        workspaceSymbols().didChange(source, symbols.toSeq)

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
    tables().jarSymbols.getTopLevels(path) match {
      case Some(toplevels) =>
        val dialect = ScalaVersions.dialectForDependencyJar(path.filename)
        definitionIndex.addIndexedSourceJar(path, toplevels, dialect)
      case None =>
        val dialect = ScalaVersions.dialectForDependencyJar(path.filename)
        val toplevels = definitionIndex.addSourceJar(path, dialect)
        tables().jarSymbols.putTopLevels(path, toplevels)
    }
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
