package scala.meta.internal.metals

import java.nio.file.Path
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspConfigGenerationStatus.BspConfigGenerationStatus
import scala.meta.internal.bsp.BspConfigGenerationStatus.Cancelled
import scala.meta.internal.bsp.BspConfigGenerationStatus.Failed
import scala.meta.internal.bsp.BspConfigGenerationStatus.Generated
import scala.meta.internal.bsp.BspConfigGenerator
import scala.meta.internal.bsp.BspConnector
import scala.meta.internal.bsp.BspServers
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BloopInstallProvider
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.VersionRecommendation
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.doctor.MetalsServiceInfo
import scala.meta.internal.metals.watcher.FileWatcherEvent
import scala.meta.internal.metals.watcher.FileWatcherEvent.EventType
import scala.meta.internal.metals.watcher.ProjectFileWatcher
import scala.meta.internal.mtags.SemanticdbPath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semver.SemVer
import scala.meta.internal.tvp.FolderTreeViewProvider
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

class ProjectMetalsLspService(
    ec: ExecutionContextExecutorService,
    sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    timerProvider: TimerProvider,
    initTreeView: () => Unit,
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    bspStatus: BspStatus,
    workDoneProgress: WorkDoneProgress,
    maxScalaCliServers: Int,
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
    ) {

  import serverInputs._

  protected val buildTools: BuildTools = new BuildTools(
    folder,
    bspGlobalDirectories,
    () => userConfig,
    () => tables.buildServers.selectedServer().nonEmpty,
    charset,
  )

  val isImportInProcess = new AtomicBoolean(false)
  val isConnecting = new AtomicBoolean(false)
  val willGenerateBspConfig = new AtomicReference(Set.empty[util.UUID])

  def withWillGenerateBspConfig[T](body: => Future[T]): Future[T] = {
    val uuid = util.UUID.randomUUID()
    willGenerateBspConfig.updateAndGet(_ + uuid)
    body.map { result =>
      willGenerateBspConfig.updateAndGet(_ - uuid)
      result
    }
  }

  override val fileWatcher: ProjectFileWatcher = register(
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

  protected val bloopInstall: BloopInstall = new BloopInstall(
    folder,
    languageClient,
    buildTools,
    tables,
    shellRunner,
    () => userConfig,
  )

  protected val bspConfigGenerator: BspConfigGenerator = new BspConfigGenerator(
    folder,
    languageClient,
    shellRunner,
    statusBar,
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

  protected val onBuildChanged: BatchedFunction[AbsolutePath, Unit] =
    BatchedFunction.fromFuture[AbsolutePath, Unit](
      onBuildChangedUnbatched,
      "onBuildChanged",
    )

  val pauseables: Pauseable = Pauseable.fromPausables(
    onBuildChanged ::
      parseTrees ::
      compilations.pauseables
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
    () => bspConnector.resolve(buildTool),
    buildTools,
  )

  protected def isMillBuildSc(path: AbsolutePath): Boolean =
    path.toNIO.getFileName.toString == "build.sc" &&
      // for now, this only checks for build.sc, but this could be made more strict in the future
      // (require ./mill or ./.mill-version)
      buildTools.isMill

  protected val bloopServers: BloopServers = new BloopServers(
    buildClient,
    languageClient,
    tables,
    clientConfig.initialConfig,
    workDoneProgress,
  )

  protected val bspServers: BspServers = new BspServers(
    folder,
    charset,
    languageClient,
    buildClient,
    tables,
    bspGlobalDirectories,
    clientConfig.initialConfig,
    () => userConfig,
    workDoneProgress,
  )

  protected val bspConnector: BspConnector = new BspConnector(
    bloopServers,
    bspServers,
    buildTools,
    languageClient,
    tables,
    () => userConfig,
    statusBar,
    workDoneProgress,
    bspConfigGenerator,
    () => bspSession.map(_.mainConnection),
    restartBspServer,
    connectionBspStatus,
  )

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

  def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    val chosenBuildServer = tables.buildServers.selectedServer()
    def useBuildToolBsp(buildTool: BuildTool) =
      buildTool match {
        case _: BloopInstallProvider => userConfig.defaultBspToBuildTool
        case _: BuildServerProvider => true
        case _ => false
      }

    def isSelected(buildTool: BuildTool) =
      buildTool match {
        case _: BuildServerProvider =>
          chosenBuildServer.contains(buildTool.buildServerName)
        case _ => false
      }

    supportedBuildTool().flatMap {
      case Some(BuildTool.Found(buildTool: BloopInstallProvider, digest))
          if chosenBuildServer.contains(BloopServers.name) ||
            chosenBuildServer.isEmpty && !useBuildToolBsp(buildTool) =>
        slowConnectToBloopServer(forceImport, buildTool, digest)
      case Some(found)
          if isSelected(found.buildTool) &&
            found.buildTool.isBspGenerated(folder) =>
        indexer.reloadWorkspaceAndIndex(
          forceImport,
          found.buildTool,
          found.digest,
          importBuild,
          reconnectToBuildServer = () =>
            if (!isConnecting.get()) quickConnectToBuildServer()
            else {
              scribe.warn("Cannot reload build session, still connecting...")
              Future.successful(BuildChange.None)
            },
        )
      case Some(BuildTool.Found(buildTool: BuildServerProvider, _)) =>
        slowConnectToBuildToolBsp(buildTool, forceImport, isSelected(buildTool))
      // Used when there are multiple `.bsp/<name>.json` configs and a known build tool (e.g. sbt)
      case Some(BuildTool.Found(buildTool, _))
          if buildTool.isBspGenerated(folder) =>
        maybeChooseServer(buildTool.buildServerName, isSelected(buildTool))
        quickConnectToBuildServer()
      // Used in tests, `.bloop` folder exists but no build tool is detected
      case _ => quickConnectToBuildServer()
    }
  }

  protected def slowConnectToBuildToolBsp(
      buildTool: BuildServerProvider,
      forceImport: Boolean,
      isSelected: Boolean,
  ): Future[BuildChange] = {
    val notification = tables.dismissedNotifications.ImportChanges
    if (buildTool.isBspGenerated(folder)) {
      maybeChooseServer(buildTool.buildServerName, isSelected)
      quickConnectToBuildServer()
    } else if (
      userConfig.shouldAutoImportNewProject || forceImport || isSelected ||
      buildTool.isInstanceOf[ScalaCliBuildTool]
    ) {
      maybeChooseServer(buildTool.buildServerName, isSelected)
      generateBspAndConnect(buildTool)
    } else if (notification.isDismissed) {
      Future.successful(BuildChange.None)
    } else {
      scribe.debug("Awaiting user response...")
      languageClient
        .showMessageRequest(
          Messages.GenerateBspAndConnect
            .params(buildTool.executableName, buildTool.buildServerName)
        )
        .asScala
        .flatMap { item =>
          if (item == Messages.dontShowAgain) {
            notification.dismissForever()
            Future.successful(BuildChange.None)
          } else if (item == Messages.GenerateBspAndConnect.yes) {
            maybeChooseServer(buildTool.buildServerName, isSelected)
            generateBspAndConnect(buildTool)
          } else {
            notification.dismiss(2, util.concurrent.TimeUnit.MINUTES)
            Future.successful(BuildChange.None)
          }
        }
    }
  }

  protected def maybeChooseServer(name: String, alreadySelected: Boolean): Any =
    if (alreadySelected) Future.successful(())
    else tables.buildServers.chooseServer(name)

  protected def generateBspAndConnect(
      buildTool: BuildServerProvider
  ): Future[BuildChange] =
    withWillGenerateBspConfig {
      buildTool
        .generateBspConfig(
          folder,
          args => bspConfigGenerator.runUnconditionally(buildTool, args),
          statusBar,
        )
        .flatMap(_ => quickConnectToBuildServer())
    }

  /**
   * If there is no auto-connectable build server and no supported build tool is found
   * we assume it's a scala-cli project.
   */
  def maybeSetupScalaCli(): Future[Unit] = {
    if (
      !buildTools.isAutoConnectable()
      && buildTools.loadSupported.isEmpty
      && (folder.isScalaProject() || focusedDocument().exists(_.isScala))
    ) {
      scalaCli.setupIDE(folder)
    } else Future.successful(())
  }

  protected def slowConnectToBloopServer(
      forceImport: Boolean,
      buildTool: BloopInstallProvider,
      checksum: String,
  ): Future[BuildChange] =
    for {
      result <- {
        if (forceImport)
          bloopInstall.runUnconditionally(buildTool, isImportInProcess)
        else bloopInstall.runIfApproved(buildTool, checksum, isImportInProcess)
      }
      change <- {
        if (result.isInstalled) quickConnectToBuildServer()
        else if (result.isFailed) {
          for {
            change <-
              if (buildTools.isAutoConnectable(optProjectRoot)) {
                // TODO(olafur) try to connect but gracefully error
                languageClient.showMessage(
                  Messages.ImportProjectPartiallyFailed
                )
                // Connect nevertheless, many build import failures are caused
                // by resolution errors in one weird module while other modules
                // exported successfully.
                quickConnectToBuildServer()
              } else {
                languageClient.showMessage(Messages.ImportProjectFailed)
                Future.successful(BuildChange.Failed)
              }
          } yield change
        } else {
          Future.successful(BuildChange.None)
        }

      }
    } yield change

  override def optProjectRoot: Option[AbsolutePath] =
    buildTool.map(_.projectRoot).orElse(buildTools.bloopProject)

  def quickConnectToBuildServer(): Future[BuildChange] =
    for {
      change <-
        if (!buildTools.isAutoConnectable(optProjectRoot)) {
          scribe.warn("Build server is not auto-connectable.")
          Future.successful(BuildChange.None)
        } else {
          autoConnectToBuildServer()
        }
    } yield {
      buildServerPromise.trySuccess(())
      change
    }

  def fullConnect(): Future[Unit] = {
    buildTools.initialize()
    for {
      _ <-
        if (buildTools.isAutoConnectable(optProjectRoot))
          autoConnectToBuildServer()
        else slowConnectToBuildServer(forceImport = false)
    } yield buildServerPromise.trySuccess(())
  }

  protected def onInitialized(): Future[Unit] =
    withWillGenerateBspConfig {
      for {
        _ <- maybeSetupScalaCli()
        _ <- fullConnect()
      } yield ()
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
            buildTool match {
              case Some(sbt: SbtBuildTool) if session.main.isSbt =>
                for {
                  _ <- disconnectOldBuildServer()
                  _ <- sbt.shutdownBspServer(shellRunner)
                  _ <- sbt.generateBspConfig(
                    folder,
                    bspConfigGenerator.runUnconditionally(sbt, _),
                    statusBar,
                  )
                  _ <- autoConnectToBuildServer()
                } yield ()
              case Some(mill: MillBuildTool) if session.main.isMill =>
                for {
                  _ <- mill.generateBspConfig(
                    folder,
                    bspConfigGenerator.runUnconditionally(mill, _),
                    statusBar,
                  )
                  _ <- autoConnectToBuildServer()
                } yield ()
              case _ if session.main.isBloop =>
                slowConnectToBuildServer(forceImport = true)
              case _ => Future.successful(())
            }
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
            for {
              _ <- buildTool.generateBspConfig(
                folder,
                bspConfigGenerator.runUnconditionally(buildTool, _),
                statusBar,
              )
              _ <- quickConnectToBuildServer()
            } yield ()
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
    } else if (
      isScalaOrJava &&
      !path.isDirectory &&
      !savedFiles.isRecentlyActive(path) &&
      !buffers.contains(path)
    ) {
      event.eventType match {
        case EventType.CreateOrModify =>
          buildTargets.onCreate(path)
        case _ =>
      }
      onChange(List(path)).asJava
    } else if (path.isSemanticdb) {
      val semanticdbPath = SemanticdbPath(path)
      Future {
        event.eventType match {
          case EventType.Delete =>
            semanticDBIndexer.onDelete(semanticdbPath)
          case EventType.CreateOrModify =>
            semanticDBIndexer.onChange(semanticdbPath)
          case EventType.Overflow =>
            semanticDBIndexer.onOverflow(semanticdbPath)
        }
      }.asJava
    } else {
      CompletableFuture.completedFuture(())
    }
  }

  def restartBspServer(): Future[Boolean] = {
    def emitMessage(msg: String) = {
      languageClient.showMessage(new MessageParams(MessageType.Warning, msg))
    }
    // This is for `bloop` and `sbt`, for which `build/shutdown` doesn't shutdown the server.
    val shutdownBsp =
      bspSession match {
        case Some(session) if session.main.isBloop =>
          for {
            _ <- disconnectOldBuildServer()
          } yield bloopServers.shutdownServer()
        case Some(session) if session.main.isSbt =>
          for {
            res <- buildTool match {
              case Some(sbt: SbtBuildTool) =>
                for {
                  _ <- disconnectOldBuildServer()
                  code <- sbt.shutdownBspServer(shellRunner)
                } yield code == 0
              case _ => Future.successful(false)
            }
          } yield res
        case s => Future.successful(s.nonEmpty)
      }

    for {
      didShutdown <- shutdownBsp
      _ = if (!didShutdown) {
        bspSession match {
          case Some(session) =>
            emitMessage(
              s"Could not shutdown ${session.main.name} server. Will try to reconnect."
            )
          case None =>
            emitMessage("No build server connected. Will try to connect.")
        }
      }
      _ <- autoConnectToBuildServer()
    } yield didShutdown
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
      () => indexer.profiledIndexWorkspace(() => ()),
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
    () => slowConnectToBuildServer(forceImport = true),
    () => switchBspServer(),
  )

  def projectInfo: MetalsServiceInfo =
    MetalsServiceInfo.ProjectService(
      () => bspSession,
      () => bspConnector.resolve(buildTool),
      buildTools,
      connectionBspStatus,
    )

  def ammoniteStart(): Future[Unit] = ammonite.start()
  def ammoniteStop(): Future[Unit] = ammonite.stop()

  def switchBspServer(): Future[Unit] =
    withWillGenerateBspConfig {
      for {
        isSwitched <- bspConnector.switchBuildServer(
          folder,
          () => slowConnectToBuildServer(forceImport = true),
        )
        _ <- {
          if (isSwitched) quickConnectToBuildServer()
          else Future.successful(())
        }
      } yield ()
    }

  def resetPopupChoice(value: String): Future[Unit] =
    popupChoiceReset.reset(value)

  def interactivePopupChoiceReset(): Future[Unit] =
    popupChoiceReset.interactiveReset()

  def generateBspConfig(): Future[Unit] = {
    val servers: List[BuildServerProvider] =
      buildTools.loadSupported().collect {
        case buildTool: BuildServerProvider => buildTool
      }

    def ensureAndConnect(
        buildTool: BuildServerProvider,
        status: BspConfigGenerationStatus,
    ): Unit =
      status match {
        case Generated =>
          tables.buildServers.chooseServer(buildTool.buildServerName)
          quickConnectToBuildServer().ignoreValue
        case Cancelled => ()
        case Failed(exit) =>
          exit match {
            case Left(exitCode) =>
              scribe.error(
                s"Create of .bsp failed with exit code: $exitCode"
              )
              languageClient.showMessage(
                Messages.BspProvider.genericUnableToCreateConfig
              )
            case Right(message) =>
              languageClient.showMessage(
                Messages.BspProvider.unableToCreateConfigFromMessage(
                  message
                )
              )
          }
      }

    (servers match {
      case Nil =>
        scribe.warn(Messages.BspProvider.noBuildToolFound.toString())
        languageClient.showMessage(Messages.BspProvider.noBuildToolFound)
        Future.successful(())
      case buildTool :: Nil =>
        withWillGenerateBspConfig {
          buildTool
            .generateBspConfig(
              folder,
              args =>
                bspConfigGenerator.runUnconditionally(
                  buildTool,
                  args,
                ),
              statusBar,
            )
            .map(status => ensureAndConnect(buildTool, status))
        }
      case buildTools =>
        withWillGenerateBspConfig {
          bspConfigGenerator
            .chooseAndGenerate(buildTools)
            .map {
              case (
                    buildTool: BuildServerProvider,
                    status: BspConfigGenerationStatus,
                  ) =>
                ensureAndConnect(buildTool, status)
            }
        }
    })
  }

  protected def buildTool: Option[BuildTool] =
    for {
      name <- tables.buildTool.selectedBuildTool()
      buildTool <- buildTools.loadSupported.find(_.executableName == name)
      found <- isCompatibleVersion(buildTool) match {
        case BuildTool.Found(bt, _) => Some(bt)
        case _ => None
      }
    } yield found

  def isCompatibleVersion(buildTool: BuildTool): BuildTool.Verified = {
    buildTool match {
      case buildTool: VersionRecommendation
          if !SemVer.isCompatibleVersion(
            buildTool.minimumVersion,
            buildTool.version,
          ) =>
        BuildTool.IncompatibleVersion(buildTool)
      case _ =>
        buildTool.digestWithRetry(folder) match {
          case Some(digest) =>
            BuildTool.Found(buildTool, digest)
          case None => BuildTool.NoChecksum(buildTool, folder)
        }
    }
  }

  def supportedBuildTool(): Future[Option[BuildTool.Found]] = {
    buildTools.loadSupported match {
      case Nil => {
        if (!buildTools.isAutoConnectable()) {
          warnings.noBuildTool()
        }
        Future(None)
      }
      case buildTools =>
        for {
          buildTool <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield {
          buildTool.flatMap { bt =>
            isCompatibleVersion(bt) match {
              case found: BuildTool.Found => Some(found)
              case warn @ BuildTool.IncompatibleVersion(buildTool) =>
                scribe.warn(warn.message)
                languageClient.showMessage(
                  Messages.IncompatibleBuildToolVersion.params(buildTool)
                )
                None
              case warn: BuildTool.NoChecksum =>
                scribe.warn(warn.message)
                None
            }
          }
        }
    }
  }

  def autoConnectToBuildServer(): Future[BuildChange] = {
    def compileAllOpenFiles: BuildChange => Future[BuildChange] = {
      case change if !change.isFailed =>
        Future
          .sequence(
            compilations
              .cascadeCompileFiles(buffers.open.toSeq)
              .ignoreValue ::
              compilers.load(buffers.open.toSeq) ::
              Nil
          )
          .map(_ => change)
      case other => Future.successful(other)
    }

    val scalaCliPaths = scalaCli.paths

    isConnecting.set(true)
    (for {
      _ <- disconnectOldBuildServer()
      maybeSession <- timerProvider.timed("Connected to build server", true) {
        bspConnector.connect(
          buildTool,
          folder,
          userConfig,
          shellRunner,
        )
      }
      result <- maybeSession match {
        case Some(session) =>
          val result = connectToNewBuildServer(session)
          session.mainConnection.onReconnection { newMainConn =>
            val updSession = session.copy(main = newMainConn)
            connectToNewBuildServer(updSession)
              .flatMap(compileAllOpenFiles)
              .ignoreValue
          }
          result
        case None =>
          Future.successful(BuildChange.None)
      }
      _ <- Future.sequence(
        scalaCliPaths
          .collect {
            case path if (!conflictsWithMainBsp(path.toNIO)) =>
              scalaCli.start(path)
          }
      )
      _ = initTreeView()
    } yield result)
      .recover { case NonFatal(e) =>
        disconnectOldBuildServer()
        val message =
          "Failed to connect with build server, no functionality will work."
        val details = " See logs for more details."
        languageClient.showMessage(
          new MessageParams(MessageType.Error, message + details)
        )
        scribe.error(message, e)
        BuildChange.Failed
      }
      .flatMap(compileAllOpenFiles)
  }

  def disconnectOldBuildServer(): Future[Unit] = {
    compilations.cancel()
    buildTargetClasses.cancel()
    diagnostics.reset()
    bspSession.foreach(connection =>
      scribe.info(s"Disconnecting from ${connection.main.name} session...")
    )

    for {
      _ <- scalaCli.stop()
      _ <- bspSession match {
        case None => Future.successful(())
        case Some(session) =>
          bspSession = None
          mainBuildTargetsData.resetConnections(List.empty)
          session.shutdown()
      }
    } yield ()
  }

  protected def connectToNewBuildServer(
      session: BspSession
  ): Future[BuildChange] = {
    scribe.info(
      s"Connected to Build server: ${session.main.name} v${session.version}"
    )
    cancelables.add(session)
    buildTool.foreach(
      workspaceReload.persistChecksumStatus(Digest.Status.Started, _)
    )
    bspSession = Some(session)
    isConnecting.set(false)
    for {
      _ <- importBuild(session)
      _ <- indexer.profiledIndexWorkspace(check)
      _ = buildTool.foreach(
        workspaceReload.persistChecksumStatus(Digest.Status.Installed, _)
      )
      _ = if (session.main.isBloop) checkRunningBloopVersion(session.version)
    } yield {
      BuildChange.Reconnected
    }
  }

  protected def buildData(): Seq[Indexer.BuildTool] =
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

  def resetWorkspace(): Future[Unit] =
    for {
      _ <- disconnectOldBuildServer()
      shouldImport = optProjectRoot match {
        case Some(path) if buildTools.isBloop(path) =>
          bloopServers.shutdownServer()
          clearBloopDir(path)
          false
        case Some(path) if buildTools.isBazelBsp =>
          clearFolders(
            path.resolve(Directories.bazelBsp),
            path.resolve(Directories.bsp),
          )
          true
        case Some(path) if buildTools.isBsp =>
          clearFolders(path.resolve(Directories.bsp))
          true
        case _ => false
      }
      _ = tables.cleanAll()
      _ <-
        if (shouldImport) slowConnectToBuildServer(true)
        else autoConnectToBuildServer().map(_ => ())
    } yield ()

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
            _ <- importBuild(session)
            _ <- indexer.profiledIndexWorkspace(check)
          } {
            focusedDocument().foreach(path => compilations.compileFile(path))
          }
      }
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
        fullConnect()
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
              () => autoConnectToBuildServer,
            )
            .flatMap { _ =>
              userConfig.bloopJvmProperties
                .map(
                  bloopServers.ensureDesiredJvmSettings(
                    _,
                    () => autoConnectToBuildServer(),
                  )
                )
                .getOrElse(Future.unit)
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

  protected def checkRunningBloopVersion(bspServerVersion: String): Unit = {
    if (doctor.isUnsupportedBloopVersion()) {
      val notification = tables.dismissedNotifications.IncompatibleBloop
      if (!notification.isDismissed) {
        val messageParams = IncompatibleBloopVersion.params(
          bspServerVersion,
          BuildInfo.bloopVersion,
          isChangedInSettings = userConfig.bloopVersion != None,
        )
        languageClient.showMessageRequest(messageParams).asScala.foreach {
          case action if action == IncompatibleBloopVersion.shutdown =>
            bloopServers.shutdownServer()
            autoConnectToBuildServer()
          case action if action == IncompatibleBloopVersion.dismissForever =>
            notification.dismissForever()
          case _ =>
        }
      }
    }
  }

  protected def onBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[Unit] =
    if (willGenerateBspConfig.get().nonEmpty) Future.unit
    else {
      val changedBuilds = paths.flatMap(buildTools.isBuildRelated)
      tables.buildTool.selectedBuildTool() match {
        // no build tool and new added
        case None if changedBuilds.nonEmpty =>
          scribe.info(s"Detected new build tool in $path")
          fullConnect()
        // used build tool changed
        case Some(chosenBuildTool) if changedBuilds.contains(chosenBuildTool) =>
          slowConnectToBuildServer(forceImport = false).ignoreValue
        // maybe new build tool added
        case Some(chosenBuildTool) if changedBuilds.nonEmpty =>
          onBuildToolsAdded(chosenBuildTool, changedBuilds)
        case _ => Future.unit
      }
    }

  protected def onBuildToolsAdded(
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
      buildToolSelector
        .onNewBuildToolAdded(newBuildTool, currentBuildTool)
        .flatMap { switch =>
          if (switch) slowConnectToBuildServer(forceImport = false)
          else Future.successful(BuildChange.None)
        }
    }.ignoreValue
    maybeBuildChange.getOrElse(Future.unit)
  }

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    val scalaCliPath = scalaCliDirOrFile(path)
    if (
      !path.isAmmoniteScript ||
      !buildTargets.inverseSources(path).isEmpty ||
      ammonite.loaded(path) ||
      scalaCli.loaded(scalaCliPath) ||
      isMillBuildSc(path)
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
    if (conflictsWithMainBsp(nioDir)) path else dir
  }

  protected def conflictsWithMainBsp(nioDir: Path): Boolean =
    buildTargets.sourceItems.filter(_.exists).exists { item =>
      val nioItem = item.toNIO
      nioDir.startsWith(nioItem) || nioItem.startsWith(nioDir)
    }

  override def startScalaCli(path: AbsolutePath): Future[Unit] = {
    super.startScalaCli(scalaCliDirOrFile(path))
  }

  override def check(): Unit = {
    super.check()
    buildTools
      .loadSupported()
      .map(_.projectRoot)
      .distinct match {
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

  override protected def resetService(): Unit = {
    super.resetService()
    treeView.reset()
  }

}
