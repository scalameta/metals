package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import scala.meta.infra.FeatureFlagProvider
import scala.meta.infra.MonitoringClient
import scala.meta.internal.bsp.BspConfigGenerator
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.doctor.MetalsServiceInfo
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.metals.watcher.FileWatcherEvent
import scala.meta.internal.metals.watcher.FileWatcherEvent.EventType
import scala.meta.internal.metals.watcher.NoopFileWatcher
import scala.meta.internal.metals.watcher.ProjectFileWatcher
import scala.meta.internal.mtags.SemanticdbPath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.tvp.FolderTreeViewProvider
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageType

class ProjectMetalsLspService(
    ec: ExecutionContextExecutorService,
    override val sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    override val languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    override val clientConfig: ClientConfiguration,
    override val statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    override val timerProvider: TimerProvider,
    initTreeView: () => Unit,
    override val folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    bspStatus: BspStatus,
    override val workDoneProgress: WorkDoneProgress,
    maxScalaCliServers: Int,
    featureFlags: FeatureFlagProvider,
    metrics: MonitoringClient,
) extends MetalsLspService(
      ec,
      sh,
      serverInputs,
      languageClient,
      initializeParams,
      clientConfig,
      statusBar,
      focusedDocument,
      shellRunner,
      timerProvider,
      folder,
      folderVisibleName,
      headDoctor,
      bspStatus,
      workDoneProgress,
      maxScalaCliServers,
      featureFlags,
      metrics,
    ) {

  scribe.debug(clientConfig.toString())

  private val SemanticdbExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  import serverInputs._

  protected val buildTools: BuildTools = new BuildTools(
    folder,
    bspGlobalDirectories,
    () => userConfig,
    () => tables.buildServers.selectedServer().nonEmpty,
    charset,
  )

  override def indexer: Indexer = connectionProvider
  def buildServerPromise = connectionProvider.buildServerPromise
  def connect[T](config: ConnectRequest): Future[BuildChange] =
    workDoneProgress.trackFuture(
      config.userMessage,
      connectionProvider.Connect.connect(config),
      metricName = config.metricName.map(name => name -> metrics),
    )

  override val fileWatcher: FileWatcher =
    if (Testing.isEnabled)
      // Only use file watcher when running tests. A lot of our tests rely on file watching notifications to pass.
      register(
        new ProjectFileWatcher(
          initialServerConfig,
          () => folder,
          buildTargets,
          fileWatchFilter,
          params => {
            didChangeWatchedFiles(params)
          },
        )
      )
    else
      // In production, rely on file watching notifications from the LSP client (VS Code, etc). Starting a file watcher
      // on every build sync ends up being expensive in large projects.
      NoopFileWatcher

  protected val bspConfigGenerator: BspConfigGenerator = new BspConfigGenerator(
    folder,
    languageClient,
    shellRunner,
    () => userConfig,
  )

  protected val fileSystemSemanticdbs: FileSystemSemanticdbs =
    new FileSystemSemanticdbs(
      buildTargets,
      charset,
      folder,
      fingerprints,
      scalaCli,
    )

  override def optFileSystemSemanticdbs(): Option[FileSystemSemanticdbs] =
    Some(fileSystemSemanticdbs)

  override protected val warnings: ProjectWarnings = new ProjectWarnings(
    folder,
    buildTargets,
    statusBar,
    clientConfig.icons,
    buildTools,
    compilations.isCurrentlyCompiling,
  )

  val buildToolProvider: BuildToolProvider = new BuildToolProvider(
    buildTools,
    tables,
    folder,
    warnings,
    languageClient,
    userConfigPromise.future.map(_ => userConfig.preferredBuildServer),
  )

  protected val bloopServers: BloopServers = new BloopServers(
    buildClient,
    languageClient,
    tables,
    clientConfig.initialConfig,
    workDoneProgress,
    sh,
  )

  val connectionProvider: ConnectionProvider = new ConnectionProvider(
    buildToolProvider,
    compilations,
    buildTools,
    buffers,
    compilers,
    scalaCli,
    bloopServers,
    shellRunner,
    bspConfigGenerator,
    check,
    doctor,
    initTreeView,
    diagnostics,
    charset,
    buildClient,
    bspGlobalDirectories,
    connectionBspStatus,
    mainBuildTargetsData,
    this,
    syncStatusReporter,
    metrics,
  )

  protected val onBuildChanged: BatchedFunction[AbsolutePath, Unit] =
    BatchedFunction.fromFuture[AbsolutePath, Unit](
      onBuildChangedUnbatched,
      "onBuildChanged",
    )

  protected val semanticdbs: Semanticdbs = AggregateSemanticdbs(
    List(
      fileSystemSemanticdbs,
      interactiveSemanticdbs,
    )
  )

  val gitHubIssueFolderInfo: GitHubIssueFolderInfo = new GitHubIssueFolderInfo(
    () => tables.buildTool.selectedBuildTool(),
    buildTargets,
    () => bspSession,
    connectionProvider.resolveBsp,
    buildTools,
  )

  protected def isMillBuildFile(path: AbsolutePath): Boolean = {
    buildTools.isMill && {
      val filename = path.toNIO.getFileName.toString
      filename == "build.mill" || filename == "build.mill.scala" || filename == "build.sc"
    }
  }

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    super
      .didChange(params)
      .asScala
      .map(_ => treeView.onWorkspaceFileDidChange(path))
      .asJava
  }

  override def didSave(
      params: DidSaveTextDocumentParams
  ): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    super
      .didSave(params)
      .asScala
      .map(_ => maybeImportScript(path))
      .map { _ =>
        treeView.onWorkspaceFileDidChange(path)
      }
      .asJava
  }

  override protected def onChange(paths: Seq[AbsolutePath]): Future[Unit] = {
    Future
      .sequence(List(super.onChange(paths), onBuildChanged(paths)))
      .ignoreValue
  }

  override def onDelete(path: AbsolutePath): Future[Unit] = {
    Future
      .sequence(
        List(
          super.onDelete(path),
          Future(treeView.onWorkspaceFileDidChange(path)),
        )
      )
      .ignoreValue
  }

  /**
   * If there is no auto-connectable build server and no supported build tool is found
   * we assume it's a scala-cli project.
   */
  def maybeSetupScalaCli(): Future[Unit] = {
    if (
      !buildTools.isAutoConnectable()
      && buildTools.loadSupported().isEmpty
      && (folder.isScalaProject() || focusedDocument().exists(_.isScala))
    ) {
      scalaCli.setupIDE(folder)
    } else Future.successful(())
  }

  protected def onInitialized(): Future[Unit] =
    connectionProvider.withWillGenerateBspConfig {
      for {
        _ <- maybeSetupScalaCli()
        _ <- connectionProvider.fullConnect()
      } yield ()
    }

  def onBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[Unit] =
    if (connectionProvider.willGenerateBspConfig) Future.unit
    else {
      val changedBuilds = paths.flatMap(buildTools.isBuildRelated)
      tables.buildTool.selectedBuildTool() match {
        // no build tool and new added
        case None if changedBuilds.nonEmpty =>
          scribe.info(s"Detected new build tool in $path")
          connectionProvider.fullConnect()
        // used build tool changed
        case Some(chosenBuildTool) if changedBuilds.contains(chosenBuildTool) =>
          connectionProvider
            .slowConnectToBuildServer(forceImport = false)
            .ignoreValue
        // maybe new build tool added
        case Some(chosenBuildTool) if changedBuilds.nonEmpty =>
          onBuildToolsAdded(chosenBuildTool, changedBuilds)
        case _ => Future.unit
      }
    }

  private def onBuildToolsAdded(
      currentBuildToolName: String,
      newBuildToolsChanged: Seq[String],
  ): Future[Unit] = {
    val supportedBuildTools = buildTools.loadSupported()
    val maybeBuildChange = for {
      currentBuildTool <- supportedBuildTools.find(
        _.executableName == currentBuildToolName
      )
      newBuildTool <- newBuildToolsChanged
        .filter(buildTools.newBuildTool)
        .flatMap(addedBuildName =>
          supportedBuildTools.find(
            _.executableName == addedBuildName
          )
        )
        .headOption
    } yield {
      buildToolProvider
        .onNewBuildToolAdded(newBuildTool, currentBuildTool)
        .flatMap { switch =>
          if (switch)
            connectionProvider.slowConnectToBuildServer(forceImport = false)
          else Future.successful(BuildChange.None)
        }
    }.ignoreValue
    maybeBuildChange.getOrElse(Future.unit)
  }

  protected def updateBspJavaHome(session: BspSession): Future[Any] = {
    if (session.main.isBazel) {
      languageClient.showMessage(
        MessageType.Warning,
        "Java home setting is not available for Bazel bsp, please use env var instead.",
      )
      Future.successful(())
    } else {
      languageClient
        .showMessageRequest(
          Messages.ProjectJavaHomeUpdate
            .params(isRestart = !session.main.isBloop)
        )
        .asScala
        .flatMap {
          case Messages.ProjectJavaHomeUpdate.restart =>
            if (session.main.isBloop)
              connectionProvider.slowConnectToBuildServer(forceImport = true)
            else
              buildToolProvider.buildTool
                .map { case bt: BuildServerProvider =>
                  connect(GenerateBspConfigAndConnect(bt, true))
                }
                .getOrElse(Future.unit)
          case Messages.ProjectJavaHomeUpdate.notNow =>
            Future.successful(())
        }
    }
  }

  protected def maybeAmendScalaCliBspConfig(
      file: AbsolutePath
  ): Future[Unit] = {
    def isScalaCli = bspSession.exists(_.main.isScalaCLI)
    def isScalaFile =
      file.toString.isScala || file.isJava || file.isAmmoniteScript
    if (
      isScalaCli && isScalaFile &&
      buildTargets.inverseSources(file).isEmpty &&
      file.toNIO.startsWith(folder.toNIO) &&
      !ScalaCliBspScope.inScope(folder, file)
    ) {
      languageClient
        .showMessageRequest(
          FileOutOfScalaCliBspScope.askToRegenerateConfigAndRestartBsp(
            file.toNIO
          )
        )
        .asScala
        .flatMap {
          case FileOutOfScalaCliBspScope.regenerateAndRestart =>
            val buildTool = ScalaCliBuildTool(folder, folder, () => userConfig)
            connect(GenerateBspConfigAndConnect(buildTool)).ignoreValue
          case _ => Future.successful(())
        }
    } else Future.successful(())
  }

  /**
   * Callback that is executed on a file change event by the file watcher.
   *
   * Note that if you are adding processing of another kind of a file, be sure
   * to include it in the [[fileWatchFilter]]
   *
   * This method is run synchronously in the FileWatcher, so it should not do
   * anything expensive on the main thread
   */
  protected def didChangeWatchedFiles(
      event: FileWatcherEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path)
    val isScalaOrJava = path.isScalaOrJava
    if (isScalaOrJava && event.eventType == EventType.Delete) {
      onDelete(path).asJava
    } else if (isScalaOrJava && !path.isDirectory) {
      val futures = List.newBuilder[Future[Unit]]
      event.eventType match {
        case EventType.CreateOrModify =>
          futures += onCreate(path).ignoreValue
        case _ =>
      }
      futures += onChange(List(path)).ignoreValue
      Future.sequence(futures.result()).ignoreValue.asJava
    } else if (path.isSemanticdb) {
      val semanticdbPath = SemanticdbPath(path)
      def changeSemanticdb = {
        event.eventType match {
          case EventType.Delete =>
            semanticDBIndexer.onDelete(semanticdbPath)
          case EventType.CreateOrModify =>
            semanticDBIndexer.onChange(semanticdbPath)
          case EventType.Overflow =>
            semanticDBIndexer.onOverflow(semanticdbPath)
        }
      }
      Future(changeSemanticdb)(SemanticdbExecutionContext).asJava
    } else {
      CompletableFuture.completedFuture(())
    }
  }

  private val ammonite: Ammonite = register {
    val amm = new Ammonite(
      buffers,
      compilers,
      compilations,
      workDoneProgress,
      diagnostics,
      tables,
      languageClient,
      buildClient,
      () => userConfig,
      () => indexer.index(() => ()),
      () => folder,
      focusedDocument,
      clientConfig.initialConfig,
      scalaVersionSelector,
      parseTreesAndPublishDiags,
    )
    buildTargets.addData(amm.buildTargetsData)
    amm
  }

  private val popupChoiceReset: PopupChoiceReset = new PopupChoiceReset(
    tables,
    languageClient,
    headDoctor.executeRefreshDoctor,
    () => connectionProvider.slowConnectToBuildServer(forceImport = true),
    () => switchBspServer(),
  )

  def projectInfo: MetalsServiceInfo =
    MetalsServiceInfo.ProjectService(
      () => bspSession,
      connectionProvider.resolveBsp,
      buildTools,
      connectionBspStatus,
      () => getProjectsJavaInfo,
      featureFlags,
    )

  private def getProjectsJavaInfo: Option[JavaInfo] = {
    val fromScalaTarget =
      for {
        scalaTarget <- mainBuildTargetsData.allScala.headOption
        home <- scalaTarget.jvmHome.flatMap(_.toAbsolutePathSafe)
        version <- scalaTarget.jvmVersion
          .flatMap(JdkVersion.parse)
          .orElse(JdkVersion.maybeJdkVersionFromJavaHome(Some(home)))
      } yield JavaInfo(home.toString(), version)

    fromScalaTarget.orElse {
      val userJavaHome =
        bspSession.flatMap {
          // we don't respect `userConfig.javaHome` for Bazel
          case bs if bs.main.isBazel => None
          case _ => userConfig.javaHome
        }
      JavaInfo.getInfo(userJavaHome)
    }
  }

  def ammoniteStart(): Future[Unit] = ammonite.start()
  def ammoniteStop(): Future[Unit] = ammonite.stop()

  def switchBspServer(): Future[Unit] =
    connectionProvider.switchBspServer()

  def resetPopupChoice(value: String): Future[Unit] =
    popupChoiceReset.reset(value)

  def interactivePopupChoiceReset(): Future[Unit] =
    popupChoiceReset.interactiveReset()

  def generateBspConfig(): Future[Unit] = {
    val servers: List[BuildServerProvider] =
      buildTools.loadSupported().collect {
        case buildTool: BuildServerProvider => buildTool
      }

    (servers match {
      case Nil =>
        scribe.warn(Messages.BspProvider.noBuildToolFound.toString())
        languageClient.showMessage(Messages.BspProvider.noBuildToolFound)
        Future.successful(())
      case buildTool :: Nil =>
        connect(
          GenerateBspConfigAndConnect(buildTool)
        ).ignoreValue
      case buildTools =>
        for {
          Some(buildTool) <- bspConfigGenerator.chooseBuildServerProvider(
            buildTools
          )
          _ <- connect(
            GenerateBspConfigAndConnect(buildTool)
          )
        } yield ()
    })
  }

  def buildData(): Seq[Indexer.BuildTool] =
    Seq(
      Indexer.BuildTool(
        "main",
        mainBuildTargetsData,
        ImportedBuild.fromList(
          bspSession.map(_.lastImportedBuild).getOrElse(Nil)
        ),
      ),
      Indexer.BuildTool(
        "ammonite",
        ammonite.buildTargetsData,
        ammonite.lastImportedBuild,
      ),
    ) ++ scalaCli.lastImportedBuilds.map {
      case (lastImportedBuild, buildTargetsData) =>
        Indexer
          .BuildTool("scala-cli", buildTargetsData, lastImportedBuild)
    }

  def resetWorkspace(): Future[Unit] = {
    for {
      _ <- connect(Disconnect(true))
      _ = clearBuildToolFolders()
      _ = tables.cleanAll()
      _ <- connectionProvider.fullConnect()
    } yield ()
  }

  private def clearBuildToolFolders(): Unit = {
    buildTools.dbBspPath match {
      case Some(dir) =>
        clearFolders(dir)
        return
      case _ =>
    }

    optProjectRoot match {
      // NOTE(olafurpg): optProjectRoot is seemingly always None?
      case Some(path) if buildTools.isBloop(path) =>
        clearBloopDir(path)
      case Some(path) if buildTools.isBazelBsp =>
        clearFolders(
          path.resolve(Directories.bazelBsp),
          path.resolve(Directories.bsp),
        )
      case Some(path) if buildTools.isBsp =>
        clearFolders(path.resolve(Directories.bsp))
      case _ =>
    }
  }

  val treeView =
    new FolderTreeViewProvider(
      new Folder(folder, folderVisibleName, true),
      buildTargets,
      definitionIndex,
      () => userConfig,
      scalaVersionSelector,
      languageClient,
      clientConfig,
      trees,
      buffers,
    )

  protected def onBuildTargetChanges(
      params: b.DidChangeBuildTarget
  ): Unit = {
    // Make sure that no compilation is running, if it is it might not get completed properly
    compilations.cancel()
    val (ammoniteChanges, otherChanges) =
      params.getChanges.asScala.partition { change =>
        val connOpt = buildTargets.buildServerOf(change.getTarget)
        connOpt.nonEmpty && connOpt == ammonite.buildServer
      }

    val scalaCliServers = scalaCli.servers
    val groupedByServer = otherChanges.groupBy { change =>
      val connOpt = buildTargets.buildServerOf(change.getTarget)
      connOpt.flatMap(conn => scalaCliServers.find(_ == conn))
    }
    val scalaCliAffectedServers = groupedByServer.collect {
      case (Some(server), _) => server
    }
    val mainConnectionChanges = groupedByServer.get(None)

    if (ammoniteChanges.nonEmpty)
      ammonite.importBuild().onComplete {
        case Success(()) =>
        case Failure(exception) =>
          scribe.error("Error re-importing Ammonite build", exception)
      }

    importAfterScalaCliChanges(scalaCliAffectedServers)

    if (mainConnectionChanges.nonEmpty) {
      bspSession match {
        case None => scribe.warn("No build server connected")
        case Some(session) =>
          for {
            _ <- connect(ImportBuildAndIndex(session))
          } {
            if (userConfig.buildOnFocus) {
              focusedDocument().foreach(path => compilations.compileFile(path))
            }
          }
      }
    } else {
      syncStatusReporter.importFinished(focusedDocument().map(_.toString))
    }
  }

  override def onUserConfigUpdate(
      newConfig: UserConfiguration
  ): Future[Unit] = {
    val old = userConfig
    super.onUserConfigUpdate(newConfig)
    val slowConnect =
      if (userConfig.customProjectRoot != old.customProjectRoot) {
        tables.buildTool.reset()
        tables.buildServers.reset()
        connectionProvider.fullConnect()
      } else Future.successful(())

    val resetDecorations =
      if (userConfig.inlayHintsOptions != old.inlayHintsOptions) {
        languageClient.refreshInlayHints().asScala
      } else Future.successful(())

    val restartBuildServer = bspSession
      .map { session =>
        if (session.main.isBloop) {
          bloopServers
            .ensureDesiredVersion(
              userConfig.currentBloopVersion,
              session.version,
              userConfig.bloopVersion.nonEmpty,
              old.bloopVersion.isDefined,
              () => connect(CreateSession(shutdownBuildServer = true)),
            )
            .flatMap { _ =>
              bloopServers.checkPropertiesChanged(
                old,
                newConfig,
                () => connect(CreateSession(shutdownBuildServer = true)),
              )
            }
            .flatMap { _ =>
              if (userConfig.javaHome != old.javaHome) {
                updateBspJavaHome(session)
              } else Future.unit
            }
        } else if (
          userConfig.ammoniteJvmProperties != old.ammoniteJvmProperties && buildTargets.allBuildTargetIds
            .exists(Ammonite.isAmmBuildTarget)
        ) {
          languageClient
            .showMessageRequest(Messages.AmmoniteJvmParametersChange.params())
            .asScala
            .flatMap {
              case item
                  if item == Messages.AmmoniteJvmParametersChange.restart =>
                ammonite.reload()
              case _ =>
                Future.unit
            }
        } else if (userConfig.javaHome != old.javaHome) {
          updateBspJavaHome(session)
        } else Future.unit
      }
      .getOrElse(Future.unit)

    for {
      _ <- slowConnect
      _ <- Future.sequence(List(restartBuildServer, resetDecorations))
    } yield ()
  }

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    val scalaCliPath = scalaCliDirOrFile(path)
    if (
      !path.isAmmoniteScript ||
      !buildTargets.inverseSources(path).isEmpty ||
      ammonite.loaded(path) ||
      scalaCli.loaded(scalaCliPath) ||
      isMillBuildFile(path)
    )
      None
    else {
      def doImportScalaCli(): Future[Unit] =
        scalaCli
          .start(scalaCliPath)
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedScalaCli
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Scala CLI project $scalaCliPath", e)
          }
      def doImportAmmonite(): Future[Unit] =
        ammonite
          .start(Some(path))
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedAmmonite
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Ammonite script $path", e)
          }

      val autoImportAmmonite =
        tables.dismissedNotifications.AmmoniteImportAuto.isDismissed
      val autoImportScalaCli =
        tables.dismissedNotifications.ScalaCliImportAuto.isDismissed

      def askAutoImport(notification: DismissedNotifications#Notification) =
        languageClient
          .showMessageRequest(Messages.ImportAllScripts.params())
          .asScala
          .onComplete {
            case Failure(e) =>
              scribe.warn("Error requesting automatic Scala scripts import", e)
            case Success(null) =>
              scribe.debug("Automatic Scala scripts import cancelled by user")
            case Success(resp) =>
              resp.getTitle match {
                case Messages.ImportAllScripts.importAll =>
                  notification.dismissForever()
                case _ =>
              }
          }

      val futureRes =
        if (autoImportAmmonite) {
          doImportAmmonite()
        } else if (autoImportScalaCli) {
          doImportScalaCli()
        } else {
          languageClient
            .showMessageRequest(Messages.ImportScalaScript.params())
            .asScala
            .flatMap { response =>
              if (response != null)
                response.getTitle match {
                  case Messages.ImportScalaScript.doImportAmmonite =>
                    askAutoImport(
                      tables.dismissedNotifications.AmmoniteImportAuto
                    )
                    doImportAmmonite()
                  case Messages.ImportScalaScript.doImportScalaCli =>
                    askAutoImport(
                      tables.dismissedNotifications.ScalaCliImportAuto
                    )
                    doImportScalaCli()
                  case _ => Future.unit
                }
              else {
                Future.unit
              }
            }
            .recover { e =>
              scribe.warn("Error requesting Scala script import", e)
            }
        }
      Some(futureRes)
    }
  }

  /**
   * Returns the absolute path or directory that ScalaCLI imports as ScalaCLI
   * scripts. By default, ScalaCLI tries to import the entire directory as
   * ScalaCLI scripts. However, we have to ensure that there are no clashes with
   * other existing sourceItems see:
   * https://github.com/scalameta/metals/issues/4447
   *
   * @param path
   *   the absolute path of the ScalaCLI script to import
   */
  def scalaCliDirOrFile(path: AbsolutePath): AbsolutePath = {
    val dir = path.parent
    val nioDir = dir.toNIO
    if (buildTargets.belongsToBuildTarget(nioDir)) path else dir
  }

  override def startScalaCli(path: AbsolutePath): Future[Unit] = {
    super.startScalaCli(scalaCliDirOrFile(path))
  }

  override def check(): Unit = {
    super.check()
    buildTools
      .current()
      .map(_.projectRoot)
      .toList match {
      case Nil => formattingProvider.validateWorkspace(folder)
      case paths =>
        paths.foreach(
          formattingProvider.validateWorkspace(_)
        )
    }
  }

  override protected def didCompileTarget(report: b.CompileReport): Unit = {
    if (!isReliableFileWatcher) {
      // NOTE(olafur) this step is exclusively used when running tests on
      // non-Linux computers to avoid flaky failures caused by delayed file
      // watching notifications. The SemanticDB indexer depends on file watching
      // notifications to pick up `*.semanticdb` file updates and there's no
      // reliable way to await until those notifications appear.
      for {
        targetroot <- buildTargets.targetRoots(report.getTarget)
        semanticdb = targetroot.resolve(Directories.semanticdb)
        generatedFile <- semanticdb.listRecursive
      } {
        val event = FileWatcherEvent.createOrModify(generatedFile.toNIO)
        didChangeWatchedFiles(event).get()
      }
    }
    super.didCompileTarget(report)
  }

  def maybeImportFileAndLoad(
      path: AbsolutePath,
      load: () => Future[Unit],
  ): Future[Unit] =
    for {
      _ <- maybeAmendScalaCliBspConfig(path)
      _ <- maybeImportScript(path).getOrElse(load())
    } yield ()

  override def resetService(): Unit = {
    super.resetService()
    treeView.reset()
  }

}
