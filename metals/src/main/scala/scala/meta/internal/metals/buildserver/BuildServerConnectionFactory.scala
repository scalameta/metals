package scala.meta.internal.metals.buildserver

import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.bsp.ProtocolExtension
import scala.meta.internal.bsp.sync.SyncExtension
import scala.meta.internal.bsp.sync.SyncMode
import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.LargeLauncher
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsBuildServer
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.RequestMonitorImpl
import scala.meta.internal.metals.ServerLivenessMonitor
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.bloop.BloopServers
import scala.meta.internal.metals.buildserver.BuildServerConnection.BspExtraBuildParams
import scala.meta.internal.metals.buildserver.BuildServerConnection.InitializeBuildData
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.eclipse.lsp4j.jsonrpc.MessageConsumer

abstract class BuildServerConnectionFactory(
    projectRoot: AbsolutePath,
    bspTraceRoot: AbsolutePath,
    localClient: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    requestTimeOutNotification: DismissedNotifications#Notification,
    reconnectNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig,
    protected val serverName: String,
    bspStatusOpt: Option[ConnectionBspStatus] = None,
    supportsWrappedSources: Option[Boolean] = None,
    workDoneProgress: WorkDoneProgress,
) {
  protected def connect()(implicit
      ec: ExecutionContextExecutorService
  ): Future[SocketConnection]

  protected def userConfiguration(): UserConfiguration

  def fromSockets(retry: Int = 5)(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {
    setupServer()
      .map { connection =>
        new BuildServerConnection(
          setupServer,
          connection,
          languageClient,
          requestTimeOutNotification = requestTimeOutNotification,
          reconnectNotification = reconnectNotification,
          config,
          projectRoot,
          supportsWrappedSources.getOrElse(connection.supportsWrappedSources),
          workDoneProgress,
        )
      }
      .recoverWith { case e: TimeoutException =>
        if (retry > 0) {
          scribe.warn(s"Retrying connection to the build server $serverName")
          fromSockets(retry - 1)
        } else {
          Future.failed(e)
        }
      }

  }

  private def setupServer()(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection.LauncherConnection] = {
    connect().map { case conn @ SocketConnection(_, output, input, _, _) =>
      val tracePrinter = Trace.setupTracePrinter("BSP", bspTraceRoot)
      val requestMonitorOpt =
        bspStatusOpt.map(new RequestMonitorImpl(_, serverName))
      val wrapper: MessageConsumer => MessageConsumer =
        requestMonitorOpt.map(_.wrapper).getOrElse(identity)
      val launcher =
        new LargeLauncher.Builder[MetalsBuildServer]()
          .traceMessages(tracePrinter.orNull)
          .setOutput(output)
          .setInput(input)
          .setLocalService(localClient)
          .setRemoteInterface(classOf[MetalsBuildServer])
          .setExecutorService(ec)
          .wrapMessages(wrapper(_))
          .create()
      val listening = launcher.startListening()
      val server = launcher.getRemoteProxy
      val stopListening =
        Cancelable(() => listening.cancel(false))
      val result =
        try {
          initialize(server)
        } catch {
          case NonFatal(e) =>
            conn.cancelables.foreach(_.cancel())
            stopListening.cancel()
            scribe.error(
              s"Cancelled waiting for 'build/initialize' response, cause: $e"
            )
            throw e
        }

      // For Bloop we use the `workspace/buildTargets`,
      // since the `buildTarget/compile` request with empty targets results in an error
      val ping: () => Unit =
        if (serverName == BloopServers.name || ScalaCli.names(serverName))
          () => server.workspaceBuildTargets()
        else
          () => server.buildTargetCompile(new CompileParams(Nil.asJava))

      val optServerLivenessMonitor =
        for {
          bspStatus <- bspStatusOpt
          requestMonitor <- requestMonitorOpt
        } yield new ServerLivenessMonitor(
          requestMonitor,
          ping,
          config.metalsToIdleTime,
          config.pingInterval,
          bspStatus,
        )

      BuildServerConnection.LauncherConnection(
        conn,
        server,
        result.getDisplayName(),
        stopListening,
        result.getVersion(),
        result.getCapabilities(),
        optServerLivenessMonitor,
        extractSyncModes(result),
      )
    }
  }

  private def extractSyncModes(
      result: InitializeBuildResult
  ): Option[List[SyncMode]] = {
    val gson = new Gson
    try {
      (Option(result.getDataKind), Option(result.getData)) match {
        case (Some("extensions"), Some(data)) =>
          val listType =
            new TypeToken[java.util.List[ProtocolExtension]]() {}.getType
          val tree = gson.toJsonTree(data)
          val extensions: Option[java.util.List[ProtocolExtension]] = Option(
            gson.fromJson(tree, listType)
          )
          extensions
            .flatMap(_.asScala.find(_.getKind.toLowerCase == "sync"))
            .map(ext => gson.toJsonTree(ext.getData))
            .flatMap(ext => Option(gson.fromJson(ext, classOf[SyncExtension])))
            .map(_.getModes.asScala.toList)
        case _ =>
          None
      }
    } catch {
      case _: Exception =>
        scribe.warn(
          "Failed to parse protocol extensions from build/initialize result"
        )
        None
    }
  }

  /**
   * Run build/initialize handshake
   */
  private def initialize(
      server: MetalsBuildServer
  ): InitializeBuildResult = {
    val isBazel = serverName == BazelBuildTool.bspName
    val gson = new Gson
    val (data, dataKind) =
      if (isBazel)
        (
          gson.toJsonTree(
            InitializeBuildData(
              BazelBuildTool.enabledRules(projectRoot).toArray
            )
          ),
          "bazel-data-kind",
        )
      else
        (
          gson.toJsonTree(
            BspExtraBuildParams(
              BuildInfo.javaSemanticdbVersion,
              BuildInfo.scalametaVersion,
              BuildInfo.supportedScalaVersions.asJava,
              config.enableBestEffort || userConfiguration().enableBestEffort,
            )
          ),
          "bloop-data-kind",
        )

    val capabilities = new BuildClientCapabilities(
      List("scala", "java").asJava
    )
    capabilities.setJvmCompileClasspathReceiver(true)
    val initializeResult = server.buildInitialize {
      val params = new InitializeBuildParams(
        "Metals",
        BuildInfo.metalsVersion,
        BuildInfo.bspVersion,
        projectRoot.toURI.toString,
        capabilities,
      )

      params.setData(data)
      params.setDataKind(dataKind)
      params
    }
    // Block on the `build/initialize` request because it should respond instantly by Bloop
    // and we want to fail fast if the connection is not made
    val result =
      if (serverName == BloopServers.name) {
        initializeResult.get(20, TimeUnit.SECONDS)
      } else {
        initializeResult.get(60, TimeUnit.SECONDS)
      }

    server.onBuildInitialized()
    result
  }

}
