package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
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
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.io.AbsolutePath

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
  var buildServerPromise: Promise[Unit] = Promise[Unit]()
  val isConnecting = new AtomicBoolean(false)

  override def index(check: () => Unit): Future[Unit] = connect(
    Index(check)
  ).ignoreValue
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
            chosenBuildServer.isEmpty && !useBuildToolBsp(buildTool) =>
        connect(
          new BloopInstallAndConnect(
            buildTool,
            digest,
            forceImport,
            shutdownServer = false,
          )
        )
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
            .params(buildTool.executableName, buildTool.buildServerName)
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
    def connect[T](request: ConnectRequest): Future[BuildChange] = {
      request match {
        case Disconnect(shutdownBuildServer) => disconnect(shutdownBuildServer)
        case Index(check) => index(check)
        case ImportBuildAndIndex(session) => importBuildAndIndex(session)
        case ConnectToSession(session) => connectToSession(session)
        case CreateSession(shutdownBuildServer) =>
          createSession(shutdownBuildServer)
        case GenerateBspConfigAndConnect(buildTool, shutdownServer) =>
          generateBspConfigAndConnect(buildTool, shutdownServer)
        case BloopInstallAndConnect(
              buildTool,
              checksum,
              forceImport,
              shutdownServer,
            ) =>
          bloopInstallAndConnect(
            buildTool,
            checksum,
            forceImport,
            shutdownServer,
          )
      }
    }

    private def disconnect(
        shutdownBuildServer: Boolean
    ): Future[BuildChange] = {
      def shutdownBsp(optMainBsp: Option[String]): Future[Boolean] = {
        optMainBsp match {
          case Some(BloopServers.name) =>
            Future { bloopServers.shutdownServer() }
          case Some(SbtBuildTool.name) =>
            for {
              res <- buildToolProvider.buildTool match {
                case Some(sbt: SbtBuildTool) =>
                  sbt.shutdownBspServer(shellRunner).map(_ == 0)
                case _ => Future.successful(false)
              }
            } yield res
          case s => Future.successful(s.nonEmpty)
        }
      }

      compilations.cancel()
      buildTargetClasses.cancel()
      diagnostics.reset()
      bspSession.foreach(connection =>
        scribe.info(s"Disconnecting from ${connection.main.name} session...")
      )

      for {
        _ <- scalaCli.stop()
        optMainBsp <- bspSession match {
          case None => Future.successful(None)
          case Some(session) =>
            bspSession = None
            mainBuildTargetsData.resetConnections(List.empty)
            session.shutdown().map(_ => Some(session.main.name))
        }
        _ <-
          if (shutdownBuildServer) shutdownBsp(optMainBsp)
          else Future.successful(())
      } yield BuildChange.None
    }

    private def index(check: () => Unit): Future[BuildChange] =
      profiledIndexWorkspace(check).map(_ => BuildChange.None)

    private def importBuildAndIndex(
        session: BspSession
    ): Future[BuildChange] = {
      val importedBuilds0 = timerProvider.timed("Imported build") {
        session.importBuilds()
      }
      for {
        bspBuilds <- workDoneProgress.trackFuture(
          Messages.importingBuild,
          importedBuilds0,
        )
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

    private def connectToSession(session: BspSession): Future[BuildChange] = {
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
            BuildInfo.bloopVersion,
            isChangedInSettings = userConfig.bloopVersion != None,
          )
          languageClient.showMessageRequest(messageParams).asScala.foreach {
            case action if action == IncompatibleBloopVersion.shutdown =>
              connect(new CreateSession(true))
            case action if action == IncompatibleBloopVersion.dismissForever =>
              notification.dismissForever()
            case _ =>
          }
        }
      }
    }

    def createSession(shutdownServer: Boolean): Future[BuildChange] = {
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
        _ <- disconnect(shutdownServer)
        maybeSession <- timerProvider.timed(
          "Connected to build server",
          true,
        ) {
          bspConnector.connect(
            buildToolProvider.buildTool,
            folder,
            () => userConfig,
            shellRunner,
          )
        }
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
            Future.successful(BuildChange.None)
        }
        _ <- Future.sequence(
          scalaCliPaths
            .collect {
              case path if (!buildTargets.belongsToBuildTarget(path.toNIO)) =>
                scalaCli.start(path)
            }
        )
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
        .flatMap(compileAllOpenFiles)
        .map { res =>
          buildServerPromise.trySuccess(())
          res
        }
    }

    private def generateBspConfigAndConnect(
        buildTool: BuildServerProvider,
        shutdownServer: Boolean,
    ): Future[BuildChange] = {
      tables.buildTool.chooseBuildTool(buildTool.executableName)
      maybeChooseServer(buildTool.buildServerName, alreadySelected = false)
      for {
        _ <-
          if (shutdownServer) disconnect(shutdownServer)
          else Future.unit
        status <- buildTool
          .generateBspConfig(
            folder,
            args => bspConfigGenerator.runUnconditionally(buildTool, args),
            statusBar,
          )
        shouldConnect = handleGenerationStatus(buildTool, status)
        status <-
          if (shouldConnect) createSession(false)
          else Future.successful(BuildChange.Failed)
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

    val isImportInProcess = new AtomicBoolean(false)

    private def bloopInstallAndConnect(
        buildTool: BloopInstallProvider,
        checksum: String,
        forceImport: Boolean,
        shutdownServer: Boolean,
    ): Future[BuildChange] = {
      for {
        result <- {
          if (forceImport)
            bloopInstall.runUnconditionally(
              buildTool,
              isImportInProcess,
            )
          else
            bloopInstall.runIfApproved(
              buildTool,
              checksum,
              isImportInProcess,
            )
        }
        change <- {
          if (result.isInstalled) createSession(shutdownServer)
          else if (result.isFailed) {
            for {
              change <-
                if (
                  buildTools.isAutoConnectable(
                    buildToolProvider.optProjectRoot
                  )
                ) {
                  // TODO(olafur) try to connect but gracefully error
                  languageClient.showMessage(
                    Messages.ImportProjectPartiallyFailed
                  )
                  // Connect nevertheless, many build import failures are caused
                  // by resolution errors in one weird module while other modules
                  // exported successfully.
                  createSession(shutdownServer)
                } else {
                  languageClient.showMessage(Messages.ImportProjectFailed)
                  Future.successful(BuildChange.Failed)
                }
            } yield change
          } else Future.successful(BuildChange.None)
        }
      } yield change
    }
  }
}

sealed trait ConnectKind
object SlowConnect extends ConnectKind

sealed trait ConnectRequest extends ConnectKind

case class Disconnect(shutdownBuildServer: Boolean) extends ConnectRequest
case class Index(check: () => Unit) extends ConnectRequest
case class ImportBuildAndIndex(bspSession: BspSession) extends ConnectRequest
case class ConnectToSession(bspSession: BspSession) extends ConnectRequest
case class CreateSession(shutdownBuildServer: Boolean = false)
    extends ConnectRequest
case class GenerateBspConfigAndConnect(
    buildTool: BuildServerProvider,
    shutdownServer: Boolean = false,
) extends ConnectRequest
case class BloopInstallAndConnect(
    buildTool: BloopInstallProvider,
    checksum: String,
    forceImport: Boolean,
    shutdownServer: Boolean,
) extends ConnectRequest
