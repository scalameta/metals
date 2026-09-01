package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.control.NonFatal

import scala.meta.internal.bsp
import scala.meta.internal.bsp.BspConfigGenerationStatus.BspConfigGenerationStatus
import scala.meta.internal.bsp.BspConnector
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BloopInstallProvider
import scala.meta.internal.builds.BspOnly
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.Digest
import scala.meta.internal.builds.Digest.Status
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Interruptable._
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

class ConnectionProvider(
    buildToolProvider: BuildToolProvider,
    compilations: Compilations,
    buildTools: BuildTools,
    buffers: Buffers,
    compilers: Compilers,
    scalaCli: ScalaCliServers,
    bloopServers: BloopServers,
    shellRunner: ShellRunner,
    bspConfigGenerator: bsp.BspConfigGenerator,
    check: () => Unit,
    doctor: Doctor,
    initTreeView: () => Unit,
    diagnostics: Diagnostics,
    charset: Charset,
    buildClient: MetalsBuildClient,
    bspGlobalDirectories: List[AbsolutePath],
    bspStatus: bsp.ConnectionBspStatus,
    mainBuildTargetsData: TargetData,
    indexProviders: IndexProviders,
)(implicit ec: ExecutionContextExecutorService, rc: ReportContext)
    extends Indexer(indexProviders)
    with Cancelable {
  import Connect.connect
  import indexProviders._

  def resolveBsp(): bsp.BspResolvedResult =
    bspConnector.resolve(buildToolProvider.buildTool)

  private val willGenerateBspConfig_ = new AtomicReference(Set.empty[UUID])
  def willGenerateBspConfig: Boolean = willGenerateBspConfig_.get().nonEmpty

  def withWillGenerateBspConfig[T](body: => Future[T]): Future[T] = {
    val uuid = UUID.randomUUID()
    willGenerateBspConfig_.updateAndGet(_ + uuid)
    body.map { result =>
      willGenerateBspConfig_.updateAndGet(_ - uuid)
      result
    }
  }

  protected val bspServers: bsp.BspServers = new bsp.BspServers(
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

  val bspConnector: BspConnector = new BspConnector(
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
    () => connect(new CreateSession(true)).ignoreValue,
    bspStatus,
  )

  private val bloopInstall: BloopInstall = new BloopInstall(
    folder,
    languageClient,
    buildTools,
    tables,
    shellRunner,
    () => userConfig,
  )

  val cancelables = new MutableCancelable
  // Can be set only in tests
  var buildServerPromise: Promise[Unit] = Promise[Unit]()
  val isConnecting = new AtomicBoolean(false)

  override def index(check: () => Unit): Future[Unit] =
    connect(Index(check)).ignoreValue

  override def cancel(): Unit = {
    cancelables.cancel()
  }

  def fullConnect(): Future[Unit] = {
    buildTools.initialize()
    for {
      _ <-
        if (buildTools.isAutoConnectable(buildToolProvider.optProjectRoot))
          connect(CreateSession())
        else slowConnectToBuildServer(forceImport = false)
    } yield buildServerPromise.trySuccess(())
  }

  def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    val chosenBuildServer = tables.buildServers.selectedServer()
    def useBuildToolBsp(buildTool: BloopInstallProvider) =
      buildTool match {
        case _: BuildServerProvider => userConfig.defaultBspToBuildTool
        case _ => false
      }

    def isSelected(buildTool: BuildTool) =
      buildTool match {
        case _: BuildServerProvider =>
          chosenBuildServer.contains(buildTool.buildServerName)
        case _ => false
      }

    buildToolProvider.supportedBuildTool().flatMap {
      case Some(BuildTool.Found(buildTool: BloopInstallProvider, digest))
          if chosenBuildServer.contains(BloopServers.name) ||
            chosenBuildServer.isEmpty && !useBuildToolBsp(
              buildTool
            ) && buildTool.isBloopInstallProvider =>
        def runInstall() = connect(new BloopInstallAndConnect(buildTool))
        if (forceImport) runInstall()
        else {
          bloopInstall.runIfApproved(buildTool, digest, runInstall)
        }
      case Some(found)
          if isSelected(found.buildTool) &&
            found.buildTool.isBspGenerated(folder) =>
        reloadWorkspaceAndIndex(
          forceImport,
          found.buildTool,
          found.digest,
        )
      case Some(BuildTool.Found(buildTool: BuildServerProvider, _)) =>
        slowConnectToBuildToolBsp(buildTool, forceImport, isSelected(buildTool))
      // Used when there are multiple `.bsp/<name>.json` configs and a known build tool (e.g. sbt)
      case Some(BuildTool.Found(buildTool, _))
          if buildTool.isBspGenerated(folder) =>
        maybeChooseServer(buildTool.buildServerName, isSelected(buildTool))
        connect(CreateSession())
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
      connect(CreateSession())
    } else if (
      userConfig.shouldAutoImportNewProject || forceImport || isSelected ||
      buildTool.isInstanceOf[ScalaCliBuildTool]
    ) {
      connect(GenerateBspConfigAndConnect(buildTool))
    } else if (notification.isDismissed) {
      Future.successful(BuildChange.None)
    } else {
      scribe.debug("Awaiting user response...")
      languageClient
        .showMessageRequest(
          Messages.GenerateBspAndConnect
            .params(buildTool.executableName, buildTool.buildServerName),
          defaultTo = () => { Messages.GenerateBspAndConnect.yes },
        )
        .asScala
        .flatMap { item =>
          if (item == Messages.dontShowAgain) {
            notification.dismissForever()
            Future.successful(BuildChange.None)
          } else if (item == Messages.GenerateBspAndConnect.yes) {
            connect(GenerateBspConfigAndConnect(buildTool))
          } else {
            notification.dismiss(2, TimeUnit.MINUTES)
            Future.successful(BuildChange.None)
          }
        }
    }
  }

  def quickConnectToBuildServer(): Future[BuildChange] =
    for {
      change <-
        if (!buildTools.isAutoConnectable(buildToolProvider.optProjectRoot)) {
          scribe.warn("Build server is not auto-connectable.")
          Future.successful(BuildChange.None)
        } else {
          connect(CreateSession())
        }
    } yield {
      buildServerPromise.trySuccess(())
      change
    }

  private def maybeChooseServer(name: String, alreadySelected: Boolean): Any =
    if (alreadySelected) Future.successful(())
    else tables.buildServers.chooseServer(name)

  private def reloadWorkspaceAndIndex(
      forceRefresh: Boolean,
      buildTool: BuildTool,
      checksum: String,
  ): Future[BuildChange] = {
    def reloadAndIndex(session: BspSession): Future[BuildChange] = {
      workspaceReload.persistChecksumStatus(Status.Started, buildTool)

      buildTool.ensurePrerequisites(folder)
      buildTool match {
        case _: BspOnly =>
          connect(CreateSession())
        case _ if !session.canReloadWorkspace =>
          connect(CreateSession())
        case _ =>
          session.workspaceReload
            .flatMap(_ => connect(new ImportBuildAndIndex(session)))
            .map { _ =>
              scribe.info("Correctly reloaded workspace")
              workspaceReload.persistChecksumStatus(
                Status.Installed,
                buildTool,
              )
              BuildChange.Reloaded
            }
            .recoverWith { case NonFatal(e) =>
              scribe.error(s"Unable to reload workspace: ${e.getMessage()}")
              workspaceReload.persistChecksumStatus(Status.Failed, buildTool)
              languageClient.showMessage(Messages.ReloadProjectFailed)
              Future.successful(BuildChange.Failed)
            }
      }
    }

    bspSession match {
      case None =>
        scribe.warn(
          "No build session currently active to reload. Attempting to reconnect."
        )
        quickConnectToBuildServer()
      case Some(session) if forceRefresh => reloadAndIndex(session)
      case Some(session) =>
        workspaceReload.oldReloadResult(checksum) match {
          case Some(status) =>
            scribe.info(s"Skipping reload with status '${status.name}'")
            Future.successful(BuildChange.None)
          case None =>
            if (userConfig.automaticImportBuild == AutoImportBuildKind.All) {
              reloadAndIndex(session)
            } else {
              for {
                userResponse <- workspaceReload.requestReload(
                  buildTool,
                  checksum,
                )
                installResult <- {
                  if (userResponse.isYes) {
                    reloadAndIndex(session)
                  } else {
                    tables.dismissedNotifications.ImportChanges
                      .dismiss(2, TimeUnit.MINUTES)
                    Future.successful(BuildChange.None)
                  }
                }
              } yield installResult
            }
        }
    }
  }

  object Connect {
    class RequestInfo(val request: ConnectRequest) {
      val promise: Promise[BuildChange] = Promise()
      val cancelPromise: Promise[Unit] = Promise()
      def cancel(): Boolean = cancelPromise.trySuccess(())
    }

    @volatile private var currentRequest: Option[RequestInfo] = None
    private val queue = new ConcurrentLinkedQueue[RequestInfo]()

    def getOngoingRequest(): Option[RequestInfo] = currentRequest

    def connect[T](request: ConnectRequest): Future[BuildChange] = {
      scribe.debug(s"new connect request: ${request.toString}")
      val info = addToQueue(request)
      pollAndConnect()
      info.promise.future.map { buildChange =>
        scribe.debug(
          s"connect request: ${request.show} finished with $buildChange"
        )
        buildChange
      }
    }

    private def addToQueue(request: ConnectRequest): RequestInfo =
      synchronized {
        val info = new RequestInfo(request)
        val iter = queue.iterator()
        while (iter.hasNext()) {
          val curr = iter.next()
          request.cancelCompare(curr.request) match {
            case TakeOver => curr.cancel()
            case Yield => info.cancel()
            case _ =>
          }
        }
        queue.add(info)
        // maybe cancel ongoing
        currentRequest.foreach(ongoing =>
          if (request.cancelCompare(ongoing.request) == TakeOver) {
            ongoing.cancel()
            languageClient.cancelRequest(
              ConnectionProvider.ConnectRequestCancelationGroup
            )
          }
        )
        info
      }

    private def pollAndConnect(): Unit = {
      val optRequest = synchronized {
        if (currentRequest.isEmpty) {
          currentRequest = Option(queue.poll())
          currentRequest
        } else None
      }

      for (request <- optRequest) {
        implicit val cancelPromise = CancelSwitch(request.cancelPromise)
        val result =
          if (request.cancelPromise.isCompleted)
            Interruptable.successful(BuildChange.Cancelled)
          else
            request.request match {
              case Disconnect(shutdownBuildServer) =>
                disconnect(shutdownBuildServer)
              case Index(check) => index(check)
              case ImportBuildAndIndex(session) =>
                importBuildAndIndex(session)
              case ConnectToSession(session) =>
                connectToSession(session)
              case CreateSession(shutdownBuildServer) =>
                createSession(shutdownBuildServer)
              case GenerateBspConfigAndConnect(buildTool, shutdownServer) =>
                generateBspConfigAndConnect(
                  buildTool,
                  shutdownServer,
                )
              case BloopInstallAndConnect(buildTool) =>
                bloopInstallAndConnect(buildTool)
            }
        result.future.onComplete { res =>
          res match {
            case Failure(MetalsCancelException) =>
              request.promise.trySuccess(BuildChange.Cancelled)
            case _ => request.promise.tryComplete(res)
          }
          currentRequest = None
          pollAndConnect()
        }
      }
    }

    private def disconnect(
        shutdownBuildServer: Boolean
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
      def shutdownBsp(optMainBsp: Option[String]): Interruptable[Boolean] = {
        optMainBsp match {
          case Some(BloopServers.name) =>
            Interruptable.successful { bloopServers.shutdownServer() }
          case Some(SbtBuildTool.name) =>
            for {
              res <- buildToolProvider.buildTool match {
                case Some(sbt: SbtBuildTool) =>
                  sbt.shutdownBspServer(shellRunner).withInterrupt.map(_ == 0)
                case _ => Interruptable.successful(false)
              }
            } yield res
          case s => Interruptable.successful(s.nonEmpty)
        }
      }

      compilations.cancel()
      buildTargetClasses.cancel()
      diagnostics.reset()
      bspSession.foreach(connection =>
        scribe.info(s"Disconnecting from ${connection.main.name} session...")
      )

      for {
        _ <- scalaCli.stop(storeLast = true).withInterrupt
        optMainBsp <- (bspSession match {
          case None => Future.successful(None)
          case Some(session) =>
            bspSession = None
            mainBuildTargetsData.resetConnections(List.empty)
            session.shutdown().map(_ => Some(session.main.name))
        }).withInterrupt
        _ <-
          if (shutdownBuildServer) shutdownBsp(optMainBsp)
          else Interruptable.successful(())
      } yield BuildChange.None
    }

    private def index(check: () => Unit): Interruptable[BuildChange] =
      profiledIndexWorkspace(check)
        .map(_ => BuildChange.None)
        .withInterrupt

    private def importBuildAndIndex(
        session: BspSession
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
      val importedBuilds0 = timerProvider.timed("Imported build") {
        session.importBuilds()
      }
      for {
        bspBuilds <- workDoneProgress
          .trackFuture(
            Messages.importingBuild,
            importedBuilds0,
          )
          .withInterrupt
        _ = {
          val idToConnection = bspBuilds.flatMap { bspBuild =>
            val targets =
              bspBuild.build.workspaceBuildTargets.getTargets().asScala
            targets.map(t => (t.getId(), bspBuild.connection))
          }
          mainBuildTargetsData.resetConnections(idToConnection)
          saveProjectReferencesInfo(bspBuilds)
        }
        _ = compilers.cancel()
        buildChange <- index(check)
      } yield buildChange
    }

    private def saveProjectReferencesInfo(
        bspBuilds: List[BspSession.BspBuild]
    ): Unit = {
      val projectRefs = bspBuilds
        .flatMap { session =>
          session.build.workspaceBuildTargets.getTargets().asScala.flatMap {
            _.getBaseDirectory() match {
              case null | "" => None
              case path => path.toAbsolutePathSafe
            }
          }
        }
        .distinct
        .filterNot(_.startWith(folder))
      if (projectRefs.nonEmpty)
        DelegateSetting.writeProjectRef(folder, projectRefs)
    }

    private def connectToSession(
        session: BspSession
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
      scribe.info(
        s"Connected to Build server: ${session.main.name} v${session.version}"
      )
      cancelables.add(session)
      buildToolProvider.buildTool.foreach(
        workspaceReload.persistChecksumStatus(Digest.Status.Started, _)
      )
      bspSession = Some(session)
      isConnecting.set(false)
      for {
        _ <- importBuildAndIndex(session)
        _ = buildToolProvider.buildTool.foreach(
          workspaceReload.persistChecksumStatus(Digest.Status.Installed, _)
        )
        _ = if (session.main.isBloop)
          checkRunningBloopVersion(session.version)
      } yield {
        BuildChange.Reconnected
      }
    }

    private def checkRunningBloopVersion(bspServerVersion: String): Unit = {
      if (doctor.isUnsupportedBloopVersion()) {
        val notification = tables.dismissedNotifications.IncompatibleBloop
        if (!notification.isDismissed) {
          val messageParams = IncompatibleBloopVersion.params(
            bspServerVersion,
            BloopServers.minimumBloopVersion,
            isChangedInSettings = userConfig.bloopVersion != None,
          )
          languageClient
            .showMessageRequest(
              messageParams,
              defaultTo = () => { IncompatibleBloopVersion.dismissForever },
            )
            .asScala
            .foreach {
              case action if action == IncompatibleBloopVersion.shutdown =>
                connect(new CreateSession(true))
              case action
                  if action == IncompatibleBloopVersion.dismissForever =>
                notification.dismissForever()
              case _ =>
            }
        }
      }
    }

    def createSession(
        shutdownServer: Boolean
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
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

      isConnecting.set(true)
      (for {
        _ <- disconnect(shutdownServer)
        maybeSession <- timerProvider
          .timed(
            "Connected to build server",
            true,
          ) {
            // If chosen build tool was removed at any point we want to readd it
            val buildToolOpt: Future[Option[BuildTool]] =
              buildToolProvider.buildTool match {
                case Some(value) =>
                  Future.successful(Some(value))
                case None =>
                  buildToolProvider.supportedBuildTool().map(_.map(_.buildTool))
              }
            buildToolOpt.flatMap { toolOpt =>
              bspConnector.connect(
                toolOpt,
                folder,
                () => userConfig,
                shellRunner,
              )
            }
          }
          .withInterrupt
        result <- maybeSession match {
          case Some(session) =>
            val result = connectToSession(session)
            session.mainConnection.onReconnection { newMainConn =>
              val updSession = session.copy(main = newMainConn)
              connect(ConnectToSession(updSession))
                .flatMap(compileAllOpenFiles)
                .ignoreValue
            }
            result
          case None =>
            Interruptable.successful(BuildChange.None)
        }
        _ <- scalaCli
          .startForAllLastPaths(path =>
            !buildTargets.belongsToBuildTarget(path.toNIO)
          )
          .withInterrupt
        _ = initTreeView()
      } yield result)
        .recover { case NonFatal(e) =>
          disconnect(false)
          val message =
            "Failed to connect with build server, no functionality will work."
          val details = " See logs for more details."
          languageClient.showMessage(
            new MessageParams(MessageType.Error, message + details)
          )
          scribe.error(message, e)
          BuildChange.Failed
        }
        .flatMap(compileAllOpenFiles(_).withInterrupt)
        .map { res =>
          buildServerPromise.trySuccess(())
          res
        }
    }

    private def generateBspConfigAndConnect(
        buildTool: BuildServerProvider,
        shutdownServer: Boolean,
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
      tables.buildTool.chooseBuildTool(buildTool.executableName)
      maybeChooseServer(buildTool.buildServerName, alreadySelected = false)
      for {
        _ <-
          if (shutdownServer) disconnect(shutdownServer)
          else Interruptable.successful(())
        status <- buildTool
          .generateBspConfig(
            folder,
            args => bspConfigGenerator.runUnconditionally(buildTool, args),
            statusBar,
          )
          .withInterrupt
        shouldConnect = handleGenerationStatus(buildTool, status)
        status <-
          if (shouldConnect) createSession(false)
          else Interruptable.successful(BuildChange.Failed)
      } yield status
    }

    /**
     * Handles showing the user what they need to know after an attempt to
     * generate a bsp config has happened.
     */
    private def handleGenerationStatus(
        buildTool: BuildServerProvider,
        status: BspConfigGenerationStatus,
    ): Boolean = status match {
      case bsp.BspConfigGenerationStatus.Generated =>
        tables.buildServers.chooseServer(buildTool.buildServerName)
        true
      case bsp.BspConfigGenerationStatus.Cancelled => false
      case bsp.BspConfigGenerationStatus.Failed(exit) =>
        exit match {
          case Left(exitCode) =>
            scribe.error(
              s"Creation of .bsp/${buildTool.buildServerName} failed with exit code: $exitCode"
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
        false
    }

    private def bloopInstallAndConnect(
        buildTool: BloopInstallProvider
    )(implicit cancelSwitch: CancelSwitch): Interruptable[BuildChange] = {
      val logsFile = buildToolProvider.folder.resolve(Directories.log)
      val logsPath = logsFile.toURI.toString
      val logsLinesCountBefore =
        if (logsFile.exists) logsFile.readText.linesIterator.size else 0
      for {
        result <- bloopInstall.run(buildTool).withInterrupt
        change <- {
          if (result.isInstalled) createSession(shutdownServer = false)
          else if (result.isFailed) {
            for {
              change <-
                if (
                  buildTools.isAutoConnectable(
                    buildToolProvider.optProjectRoot
                  )
                ) {
                  languageClient
                    .showMessageRequest(
                      Messages.ImportProjectPartiallyFailed.params(),
                      defaultTo =
                        () => { Messages.ImportProjectPartiallyFailed.showLogs },
                    )
                    .asScala
                    .foreach {
                      case Messages.ImportProjectPartiallyFailed.showLogs =>
                        val cursorRange = new lsp4j.Range(
                          new lsp4j.Position(logsLinesCountBefore, 0),
                          new lsp4j.Position(logsLinesCountBefore, 0),
                        )
                        val location = new lsp4j.Location(logsPath, cursorRange)
                        languageClient.metalsExecuteClientCommand(
                          ClientCommands.GotoLocation
                            .toExecuteCommandParams(
                              ClientCommands.WindowLocation(
                                location.getUri(),
                                location.getRange(),
                              )
                            )
                        )
                      case _ => Interruptable.successful(BuildChange.Failed)
                    }

                  // Connect nevertheless, many build import failures are caused
                  // by resolution errors in one weird module while other modules
                  // exported successfully.
                  createSession(shutdownServer = false)
                } else {
                  buildTool match {
                    case _: BuildServerProvider =>
                      languageClient
                        .showMessageRequest(
                          Messages.ImportProjectFailedSuggestBspSwitch.params(),
                          defaultTo = () => {
                            Messages.ImportProjectFailedSuggestBspSwitch.cancel
                          },
                        )
                        .asScala
                        .foreach {
                          case Messages.ImportProjectFailedSuggestBspSwitch.switchBsp =>
                            switchBspServer()
                          case _ => Interruptable.successful(BuildChange.Failed)
                        }
                      Interruptable.successful(BuildChange.Failed)
                    case _ =>
                      languageClient.showMessage(Messages.ImportProjectFailed)
                      Interruptable.successful(BuildChange.Failed)
                  }
                }
            } yield change
          } else Interruptable.successful(BuildChange.None)
        }
      } yield change
    }
  }

  def switchBspServer(): Future[BuildChange] =
    withWillGenerateBspConfig {
      for {
        connectKind <- bspConnector.switchBuildServer()
        change <-
          connectKind match {
            case None => Future.successful(BuildChange.Failed)
            case Some(SlowConnect) =>
              slowConnectToBuildServer(forceImport = true)
            case Some(request: ConnectRequest) => connect(request)
          }
      } yield change
    }
}

sealed trait ConnectKind
object SlowConnect extends ConnectKind

sealed trait ConflictBehaviour
case object Yield extends ConflictBehaviour
case object TakeOver extends ConflictBehaviour
case object Queue extends ConflictBehaviour

sealed trait ConnectRequest extends ConnectKind {

  /**
   * Decides what to do with a new connect request
   * in presence of an another ongoing/queued request.
   * @param other the ongoing or queued request
   * @return behavoiur of the incoming request
   * Yield    -- cancel this
   * TakeOver -- cancel other
   * Queue    -- queue
   */
  def cancelCompare(other: ConnectRequest): ConflictBehaviour

  def show: String
}

case class Disconnect(shutdownBuildServer: Boolean) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour =
    other match {
      case _: Index => Queue
      case _ => Yield
    }

  def show: String = s"disconnect with shutdown=$shutdownBuildServer"
}
case class Index(check: () => Unit) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour = Yield

  def show: String = s"index"
}
case class ImportBuildAndIndex(bspSession: BspSession) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour =
    other match {
      case (_: Index) | (_: ImportBuildAndIndex) => TakeOver
      case _ => Yield
    }

  def show: String = s"import build and index for ${bspSession.main.name}"
}
case class ConnectToSession(bspSession: BspSession) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour =
    other match {
      case (_: Disconnect) | (_: Index) | (_: ConnectToSession) => TakeOver
      case _ => Yield
    }

  def show: String = s"connect to session for ${bspSession.main.name}"
}
case class CreateSession(shutdownBuildServer: Boolean = false)
    extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour =
    other match {
      case (_: Disconnect) | (_: Index) | (_: ConnectToSession) | CreateSession(
            false
          ) =>
        TakeOver
      case _ => Yield
    }

  def show: String = s"create session with shutdown=$shutdownBuildServer"
}
case class GenerateBspConfigAndConnect(
    buildTool: BuildServerProvider,
    shutdownServer: Boolean = false,
) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour = TakeOver

  def show: String =
    s"generate bsp config and connect for ${buildTool.buildServerName} with shutdown=$shutdownServer"
}
case class BloopInstallAndConnect(
    buildTool: BloopInstallProvider
) extends ConnectRequest {
  def cancelCompare(other: ConnectRequest): ConflictBehaviour =
    other match {
      case GenerateBspConfigAndConnect(_, true) => Queue
      case _ => TakeOver
    }

  def show: String =
    s"bloop install and connect for ${buildTool.buildServerName}"
}

object ConnectionProvider {
  // message requests called inside of `connect`,
  // since it's queued, they block `connect`
  val ConnectRequestCancelationGroup = "connect-request"
}
