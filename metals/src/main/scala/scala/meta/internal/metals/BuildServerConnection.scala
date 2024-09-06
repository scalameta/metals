package scala.meta.internal.metals

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.build.bsp.WrappedSourcesItem
import scala.build.bsp.WrappedSourcesParams
import scala.build.bsp.WrappedSourcesResult
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.Success

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.utils.RequestRegistry
import scala.meta.internal.metals.utils.Timeout
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import org.eclipse.lsp4j.jsonrpc.JsonRpcException
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.MessageIssueException
import org.eclipse.lsp4j.services.LanguageClient

/**
 * An actively running and initialized BSP connection
 */
class BuildServerConnection private (
    setupConnection: () => Future[
      BuildServerConnection.LauncherConnection
    ],
    initialConnection: BuildServerConnection.LauncherConnection,
    languageClient: LanguageClient,
    reconnectNotification: DismissedNotifications#Notification,
    requestTimeOutNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig,
    workspace: AbsolutePath,
    supportsWrappedSources: Boolean,
    progress: WorkDoneProgress,
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  private def timeout(minutes: Int) = Some(
    Timeout.default(FiniteDuration(minutes, TimeUnit.MINUTES))
  )

  @volatile private var connection = Future.successful(initialConnection)
  private def reestablishConnection(
      original: Future[BuildServerConnection.LauncherConnection]
  ) = {
    original.foreach(_.optLivenessMonitor.foreach(_.shutdown()))
    setupConnection()
  }

  val requestRegistry =
    new RequestRegistry(
      initialConnection.cancelables,
      languageClient,
      Some(requestTimeOutNotification),
    )

  private val isShuttingDown = new AtomicBoolean(false)
  private val onReconnection =
    new AtomicReference[BuildServerConnection => Future[Unit]](_ =>
      Future.successful(())
    )

  private val _version = new AtomicReference(initialConnection.version)

  def version: String = _version.get()

  // the name is set before when establishing connection
  def name: String = initialConnection.socketConnection.serverName
  private def capabilities: BuildServerCapabilities =
    initialConnection.capabilities

  initialConnection.setReconnect(() => reconnect().ignoreValue)

  def isBloop: Boolean = name == BloopServers.name

  def isSbt: Boolean = name == SbtBuildTool.name

  def isMill: Boolean = name == MillBuildTool.bspName

  def isBazel: Boolean = name == BazelBuildTool.bspName

  def isScalaCLI: Boolean = ScalaCli.names(name)

  def isAmmonite: Boolean = name == Ammonite.name

  def supportsLazyClasspathResolution: Boolean =
    capabilities.getJvmCompileClasspathProvider()

  def canReloadWorkspace: Boolean =
    capabilities.getCanReload()

  def supportsLanguage(id: String): Boolean =
    Option(capabilities.getCompileProvider())
      .exists(_.getLanguageIds().contains(id)) ||
      Option(capabilities.getDebugProvider())
        .exists(_.getLanguageIds().contains(id)) ||
      Option(capabilities.getRunProvider())
        .exists(_.getLanguageIds().contains(id)) ||
      Option(capabilities.getTestProvider())
        .exists(_.getLanguageIds().contains(id))

  def supportsScala: Boolean = supportsLanguage("scala")

  def supportsJava: Boolean = supportsLanguage("java")

  def isDebuggingProvider: Boolean =
    Option(capabilities.getDebugProvider())
      .exists(_.getLanguageIds().contains("scala"))

  def isJvmEnvironmentSupported: Boolean =
    capabilities.getJvmRunEnvironmentProvider()

  def isDependencySourcesSupported: Boolean =
    capabilities.getDependencySourcesProvider()

  // Scala CLI breaks when we try to use the `buildTarget/dependencyModules` request
  def isDependencyModulesSupported: Boolean =
    capabilities.getDependencyModulesProvider() && !isScalaCLI

  /* Currently only Bloop and sbt support running single test cases
   * and ScalaCLI uses Bloop underneath.
   */
  def supportsTestSelection: Boolean = isBloop || isSbt || isScalaCLI

  /* Some users may still use an old version of Bloop that relies on scala-debug-adapter 1.x.
   * Metals does not support scala-debug-adapter 1.x anymore.
   */
  def usesScalaDebugAdapter2x: Boolean = {
    def supportNewDebugAdapter = SemVer.isCompatibleVersion(
      "1.4.10",
      version,
    )
    !isBloop || (isBloop && supportNewDebugAdapter)
  }

  def workspaceDirectory: AbsolutePath = workspace

  def onReconnection(
      index: BuildServerConnection => Future[Unit]
  ): Unit = {
    onReconnection.set(index)
  }

  /**
   * Run build/shutdown procedure
   */
  def shutdown(): Future[Unit] =
    connection.map { conn =>
      try {
        if (isShuttingDown.compareAndSet(false, true)) {
          conn.server.buildShutdown().get(2, TimeUnit.SECONDS)
          conn.server.onBuildExit()
          conn.optLivenessMonitor.foreach(_.shutdown())
          scribe.info("Shut down connection with build server.")
          // Cancel pending compilations on our side, this is not needed for Bloop.
          cancel()
        }
      } catch {
        case _: TimeoutException =>
          scribe.error(
            s"timeout: build server '${conn.displayName}' during shutdown"
          )
        case InterruptException() =>
        case e: Throwable =>
          scribe.error(
            s"build shutdown: ${conn.displayName}",
            e,
          )
      }
    }

  def compile(
      params: CompileParams,
      timeout: Option[Timeout],
  ): CompletableFuture[CompileResult] = {
    register(
      server => server.buildTargetCompile(params),
      onFail = Some(
        (
          new CompileResult(StatusCode.CANCELLED),
          s"Cancelling compilation on ${name} server",
        )
      ),
      timeout = timeout,
    )
  }

  def clean(params: CleanCacheParams): CompletableFuture[CleanCacheResult] = {
    register(server => server.buildTargetCleanCache(params))
  }

  def workspaceReload(): Future[Object] = {
    if (initialConnection.capabilities.getCanReload()) {
      register(server => server.workspaceReload()).asScala
    } else {
      scribe.warn(
        s"${initialConnection.displayName} does not support `workspace/reload`, unable to reload"
      )
      Future.successful(null)
    }
  }

  def mainClasses(
      params: ScalaMainClassesParams,
      retry: Int = 3,
  ): Future[ScalaMainClassesResult] = {
    val resultOnUnsupported = new ScalaMainClassesResult(Collections.emptyList)
    if (supportsScala) {
      val onFail = Some(
        (
          resultOnUnsupported,
          "Scala main classes not supported by server",
        )
      )
      register(
        server => server.buildTargetScalaMainClasses(params),
        onFail,
        timeout(1),
      ).asScala.recoverWith {
        case _: TimeoutException if retry > 0 => mainClasses(params, retry - 1)
      }
    } else Future.successful(resultOnUnsupported)

  }

  def testClasses(
      params: ScalaTestClassesParams,
      retry: Int = 3,
  ): Future[ScalaTestClassesResult] = {
    val resultOnUnsupported = new ScalaTestClassesResult(Collections.emptyList)
    if (supportsScala) {
      val onFail = Some(
        (
          resultOnUnsupported,
          "Scala test classes not supported by server",
        )
      )
      register(
        server => server.buildTargetScalaTestClasses(params),
        onFail,
        timeout(1),
      ).asScala.recoverWith {
        case _: TimeoutException if retry > 0 => testClasses(params, retry - 1)
      }
    } else Future.successful(resultOnUnsupported)
  }

  def startDebugSession(
      params: DebugSessionParams,
      cancelPromise: Promise[Unit],
  ): Future[URI] = {
    val completableFuture = register(server => server.debugSessionStart(params))
    cancelPromise.future.foreach(_ => completableFuture.cancel(true))
    completableFuture.asScala.map(address => URI.create(address.getUri))
  }

  def jvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): Future[JvmRunEnvironmentResult] = {
    def empty = new JvmRunEnvironmentResult(Collections.emptyList)
    connection.flatMap { conn =>
      if (conn.capabilities.getJvmRunEnvironmentProvider()) {
        register(
          server => server.buildTargetJvmRunEnvironment(params),
          onFail = Some(
            (
              empty,
              s"${name} should support `buildTarget/jvmRunEnvironment`, but it fails.",
            )
          ),
        ).asScala
      } else {
        scribe.warn(
          s"${conn.displayName} does not support `buildTarget/jvmRunEnvironment`, unable to fetch run environment."
        )
        Future.successful(empty)
      }
    }
  }

  def workspaceBuildTargets(): Future[WorkspaceBuildTargetsResult] = {
    register(server => server.workspaceBuildTargets()).asScala
  }

  def buildTargetJavacOptions(
      params: JavacOptionsParams
  ): Future[JavacOptionsResult] = {
    val resultOnJavacOptionsUnsupported = new JavacOptionsResult(
      List.empty[JavacOptionsItem].asJava
    )
    if (isSbt || isAmmonite) Future.successful(resultOnJavacOptionsUnsupported)
    else {
      if (supportsJava) {
        val onFail = Some(
          (
            resultOnJavacOptionsUnsupported,
            "Java targets not supported by server",
          )
        )
        register(
          server => server.buildTargetJavacOptions(params),
          onFail,
        ).asScala
      } else Future.successful(resultOnJavacOptionsUnsupported)
    }
  }

  def buildTargetRun(
      params: RunParams,
      cancelPromise: Promise[Unit],
  ): Future[RunResult] = {
    val completableFuture = register(server => server.buildTargetRun(params))
    cancelPromise.future.foreach { _ =>
      completableFuture.cancel(true)
    }
    completableFuture.asScala
  }

  def buildTargetJvmClasspath(
      params: JvmCompileClasspathParams,
      cancelPromise: Promise[Unit],
  ): Future[JvmCompileClasspathResult] = {
    val resultOnScalaOptionsUnsupported = new JvmCompileClasspathResult(
      List.empty[JvmCompileClasspathItem].asJava
    )
    if (supportsLazyClasspathResolution) {
      val onFail =
        Some(
          (
            resultOnScalaOptionsUnsupported,
            "Jvm compile classpath request not supported by server",
          )
        )
      val completable = register(
        server => server.buildTargetJvmCompileClasspath(params),
        onFail,
      )
      cancelPromise.future.map(_ => completable.cancel(true))
      val description = if (params.getTargets().size() == 1) {
        params.getTargets().get(0).getUri()
      } else {
        s"${params.getTargets().size()} targets"
      }
      progress.trackFuture(
        s"Resolving classpath for $description",
        completable.asScala,
      )
    } else Future.successful(resultOnScalaOptionsUnsupported)
  }

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): Future[ScalacOptionsResult] = {
    val resultOnScalaOptionsUnsupported = new ScalacOptionsResult(
      List.empty[ScalacOptionsItem].asJava
    )
    if (supportsScala) {
      val onFail = Some(
        (
          resultOnScalaOptionsUnsupported,
          "Scala targets not supported by server",
        )
      )
      register(
        server => server.buildTargetScalacOptions(params),
        onFail,
      ).asScala
    } else Future.successful(resultOnScalaOptionsUnsupported)
  }

  def buildTargetSources(params: SourcesParams): Future[SourcesResult] = {
    register(server => server.buildTargetSources(params)).asScala
  }

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): Future[DependencySourcesResult] = {
    if (isDependencySourcesSupported) {
      register(server => server.buildTargetDependencySources(params)).asScala
    } else {
      scribe.warn(
        s"${initialConnection.displayName} does not support `buildTarget/dependencySources`, unable to fetch dependency sources."
      )
      val empty = new DependencySourcesResult(Collections.emptyList)
      Future.successful(empty)
    }
  }

  def buildTargetInverseSources(
      params: InverseSourcesParams
  ): Future[InverseSourcesResult] = {
    if (initialConnection.capabilities.getInverseSourcesProvider()) {
      register(server => server.buildTargetInverseSources(params)).asScala
    } else {
      scribe.warn(
        s"${initialConnection.displayName} does not support `buildTarget/inverseSources`, unable to fetch targets owning source."
      )
      val empty = new InverseSourcesResult(Collections.emptyList)
      Future.successful(empty)
    }
  }

  def buildTargetWrappedSources(
      params: WrappedSourcesParams
  ): Future[WrappedSourcesResult] = {
    if (supportsWrappedSources)
      // this calls https://github.com/VirtusLab/scala-cli/blob/6efbefb1d864c0ee36156f9ac8489d0e14ee54c4/modules/scala-cli-bsp/src/main/java/scala/build/bsp/ScalaScriptBuildServer.java#L7-L12
      register(server => server.buildTargetWrappedSources(params)).asScala
    else
      Future.successful(
        new WrappedSourcesResult(List.empty[WrappedSourcesItem].asJava)
      )
  }

  def buildTargetDependencyModules(
      params: DependencyModulesParams
  ): Future[DependencyModulesResult] = {
    if (isDependencyModulesSupported)
      register(server => server.buildTargetDependencyModules(params)).asScala
    else
      Future.successful(
        new DependencyModulesResult(List.empty[DependencyModulesItem].asJava)
      )
  }

  private val cancelled = new AtomicBoolean(false)

  override def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      requestRegistry.cancel()
    }
  }

  private def askUser(
      original: Future[BuildServerConnection.LauncherConnection]
  ): Future[BuildServerConnection.LauncherConnection] = {
    if (config.askToReconnect) {
      if (!reconnectNotification.isDismissed) {
        val params = Messages.DisconnectedServer.params()
        languageClient.showMessageRequest(params).asScala.flatMap {
          case response if response == Messages.DisconnectedServer.reconnect =>
            reestablishConnection(original)
          case response if response == Messages.DisconnectedServer.notNow =>
            reconnectNotification.dismiss(5, TimeUnit.MINUTES)
            connection
          case _ =>
            connection
        }
      } else {
        connection
      }
    } else {
      reestablishConnection(original)
    }
  }

  private def reconnect(): Future[BuildServerConnection.LauncherConnection] = {
    val original = connection
    if (!isShuttingDown.get()) {
      synchronized {
        // if the future is different then the connection is already being reestablished
        if (connection eq original) {
          connection = askUser(original).map { conn =>
            // version can change when reconnecting
            _version.set(conn.version)
            requestRegistry.addOngoingRequest(conn.cancelables)
            conn.setReconnect(() => reconnect().ignoreValue)
            conn
          }
          connection.foreach(_ => onReconnection.get()(this))
        }
        connection
      }
    } else {
      connection
    }

  }

  private def register[T: ClassTag](
      action: MetalsBuildServer => CompletableFuture[T],
      onFail: => Option[(T, String)] = None,
      timeout: Option[Timeout] = None,
  ): CompletableFuture[T] = {
    val localCancelable = new MutableCancelable()
    def runWithCanceling(
        launcherConnection: BuildServerConnection.LauncherConnection
    ): Future[T] = {
      val CancelableFuture(result, cancelable) = requestRegistry.register(
        action = () => action(launcherConnection.server),
        timeout = timeout,
      )
      localCancelable.add(cancelable)
      result.onComplete(_ => localCancelable.remove(cancelable))
      result
    }

    val original = connection
    val actionFuture = original
      .flatMap { launcherConnection =>
        runWithCanceling(launcherConnection)
      }
      .recoverWith {
        case io: JsonRpcException if io.getCause.isInstanceOf[IOException] =>
          synchronized {
            reconnect().flatMap(conn => runWithCanceling(conn))
          }
        case t
            if implicitly[ClassTag[T]].runtimeClass.getSimpleName != "Object" =>
          val name = implicitly[ClassTag[T]].runtimeClass.getSimpleName
          val message = onFail
            .map { case (_, msg) => msg }
            .getOrElse(s"Failed to run request with params ${name}")

          t match {
            case _: CancellationException =>
              scribe.info(message)
            case issue: MessageIssueException =>
              scribe.info(issue.getRpcMessage().toString())
            case _ =>
              scribe.info(message, t)
          }
          onFail
            .map { case (defaultResult, _) =>
              Future.successful(defaultResult)
            }
            .getOrElse({
              Future.failed(new MetalsBspException(name, t))
            })
      }

    CancelTokens.future { token =>
      token.onCancel().asScala.onComplete {
        case Success(java.lang.Boolean.TRUE) => localCancelable.cancel()
        case _ =>
      }
      actionFuture
    }
  }

}

object BuildServerConnection {

  /**
   * Establishes a new build server connection with the given input/output streams.
   *
   * This method is blocking, doesn't return Future[], because if the `initialize` handshake
   * doesn't complete within a few seconds then something is wrong. We want to fail fast
   * when initialization is not successful.
   *
   * @param bspTraceRoot we look for  `bspTraceRoot/.metals/.bsp.trace.json` to write down bsp trace
   */
  def fromSockets(
      projectRoot: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      localClient: MetalsBuildClient,
      languageClient: MetalsLanguageClient,
      connect: () => Future[SocketConnection],
      requestTimeOutNotification: DismissedNotifications#Notification,
      reconnectNotification: DismissedNotifications#Notification,
      config: MetalsServerConfig,
      serverName: String,
      bspStatusOpt: Option[ConnectionBspStatus] = None,
      retry: Int = 5,
      supportsWrappedSources: Option[Boolean] = None,
      workDoneProgress: WorkDoneProgress,
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    def setupServer(): Future[LauncherConnection] = {
      connect().map { case conn @ SocketConnection(_, output, input, _, _) =>
        val tracePrinter = Trace.setupTracePrinter("BSP", bspTraceRoot)
        val requestMonitorOpt =
          bspStatusOpt.map(new RequestMonitorImpl(_, serverName))
        val wrapper: MessageConsumer => MessageConsumer =
          requestMonitorOpt.map(_.wrapper).getOrElse(identity)
        val launcher =
          new Launcher.Builder[MetalsBuildServer]()
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
            BuildServerConnection.initialize(
              projectRoot,
              server,
              serverName,
              config,
            )
          } catch {
            case e: TimeoutException =>
              conn.cancelables.foreach(_.cancel())
              stopListening.cancel()
              scribe.error("Timeout waiting for 'build/initialize' response")
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

        LauncherConnection(
          conn,
          server,
          result.getDisplayName(),
          stopListening,
          result.getVersion(),
          result.getCapabilities(),
          optServerLivenessMonitor,
        )
      }
    }

    setupServer()
      .map { connection =>
        new BuildServerConnection(
          setupServer,
          connection,
          languageClient,
          requestTimeOutNotification,
          reconnectNotification,
          config,
          projectRoot,
          supportsWrappedSources.getOrElse(connection.supportsWrappedSources),
          workDoneProgress,
        )
      }
      .recoverWith { case e: TimeoutException =>
        if (retry > 0) {
          scribe.warn(s"Retrying connection to the build server $serverName")
          fromSockets(
            projectRoot,
            bspTraceRoot,
            localClient,
            languageClient,
            connect,
            reconnectNotification,
            requestTimeOutNotification,
            config,
            serverName,
            bspStatusOpt,
            retry - 1,
            supportsWrappedSources,
            workDoneProgress,
          )
        } else {
          Future.failed(e)
        }
      }
  }

  final case class BspExtraBuildParams(
      javaSemanticdbVersion: String,
      semanticdbVersion: String,
      supportedScalaVersions: java.util.List[String],
      enableBestEffortMode: Boolean,
  )

  /**
   * Run build/initialize handshake
   */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer,
      serverName: String,
      config: MetalsServerConfig,
  ): InitializeBuildResult = {
    val extraParams = BspExtraBuildParams(
      BuildInfo.javaSemanticdbVersion,
      BuildInfo.scalametaVersion,
      BuildInfo.supportedScala2Versions.asJava,
      config.enableBestEffort,
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
        workspace.toURI.toString,
        capabilities,
      )

      val gson = new Gson
      val data = gson.toJsonTree(extraParams)
      params.setData(data)
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

  private case class LauncherConnection(
      socketConnection: SocketConnection,
      server: MetalsBuildServer,
      displayName: String,
      cancelServer: Cancelable,
      version: String,
      capabilities: BuildServerCapabilities,
      optLivenessMonitor: Option[ServerLivenessMonitor],
  ) {

    def cancelables: List[Cancelable] =
      cancelServer :: socketConnection.cancelables

    def setReconnect(
        reconnect: () => Future[Unit]
    )(implicit ec: ExecutionContext): Unit =
      socketConnection.finishedPromise.future.foreach(_ => reconnect())

    /**
     * Whether we can call buildTargetWrappedSources through the BSP connection.
     *
     * As much as possible, we try to call buildTargetWrappedSources through BSP only when we know
     * the build server supports it. Theoretically, we could try to call it, and catch the JSONRPC
     * error saying that endpoint isn't supported, but some build servers (sbt) don't respond
     * with an error in such a case, but rather… don't answer, and let the client timeout. Which
     * makes sbt BSP support unusable here.
     * We could also add a dedicated field for it in BuildServerCapabilities, but that requires
     * updating the build server protocol itself, which I'd rather avoid at this point, as this
     * feature is somewhat experimental.
     * The only "dynamic" way I could find to advertize that capability is via a language ids
     * field, so that's what we use here, with that "scala-sc" language.
     */
    def supportsWrappedSources: Boolean =
      capabilities.getCompileProvider.getLanguageIds.asScala
        .contains("scala-sc")
  }
}

case class SocketConnection(
    serverName: String,
    output: ClosableOutputStream,
    input: InputStream,
    cancelables: List[Cancelable],
    finishedPromise: Promise[Unit],
)
