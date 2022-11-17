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
import scala.reflect.ClassTag
import scala.util.Try

import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.pc.InterruptException
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import org.eclipse.lsp4j.jsonrpc.JsonRpcException
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient

/**
 * An actively running and initialized BSP connection.
 */
class BuildServerConnection private (
    reestablishConnection: () => Future[
      BuildServerConnection.LauncherConnection
    ],
    initialConnection: BuildServerConnection.LauncherConnection,
    languageClient: LanguageClient,
    reconnectNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig,
    workspace: AbsolutePath,
    supportsWrappedSources: Boolean,
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  @volatile private var connection = Future.successful(initialConnection)
  initialConnection.onConnectionFinished(reconnect)

  private val isShuttingDown = new AtomicBoolean(false)
  private val onReconnection =
    new AtomicReference[BuildServerConnection => Future[Unit]](_ =>
      Future.successful(())
    )

  private val _version = new AtomicReference(initialConnection.version)

  private val ongoingRequests =
    new MutableCancelable().addAll(initialConnection.cancelables)
  private val ongoingCompilations = new MutableCancelable()

  def version: String = _version.get()

  // the name is set before when establishing conenction
  def name: String = initialConnection.socketConnection.serverName

  def isBloop: Boolean = name == BloopServers.name

  def isSbt: Boolean = name == SbtBuildTool.name

  def isMill: Boolean = name == MillBuildTool.name

  def isScalaCLI: Boolean = name == ScalaCli.name

  def isAmmonite: Boolean = name == Ammonite.name

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
    isSbt || isScalaCLI || (isBloop && supportNewDebugAdapter)
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

  def compile(params: CompileParams): CompletableFuture[CompileResult] = {
    register(
      server => server.buildTargetCompile(params),
      isCompile = true,
      onFail = Some(
        (
          new CompileResult(StatusCode.CANCELLED),
          s"Cancelling compilation on ${name} server",
        )
      ),
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
      params: ScalaMainClassesParams
  ): Future[ScalaMainClassesResult] = {
    register(server => server.buildTargetScalaMainClasses(params)).asScala
  }

  def testClasses(
      params: ScalaTestClassesParams
  ): Future[ScalaTestClassesResult] = {
    register(server => server.buildTargetScalaTestClasses(params)).asScala
  }

  def startDebugSession(params: DebugSessionParams): Future[URI] = {
    register(server => server.debugSessionStart(params)).asScala
      .map(address => URI.create(address.getUri))
  }

  def jvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): Future[JvmRunEnvironmentResult] = {
    def empty = new JvmRunEnvironmentResult(Collections.emptyList)
    connection.flatMap { conn =>
      if (conn.capabilities.getJvmRunEnvironmentProvider()) {
        register(
          server => server.jvmRunEnvironment(params),
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
      val onFail = Some(
        (
          resultOnJavacOptionsUnsupported,
          "Java targets not supported by server",
        )
      )
      register(server => server.buildTargetJavacOptions(params), onFail).asScala
    }
  }

  def buildTargetScalacOptions(
      params: ScalacOptionsParams
  ): Future[ScalacOptionsResult] = {
    val resultOnScalaOptionsUnsupported = new ScalacOptionsResult(
      List.empty[ScalacOptionsItem].asJava
    )
    val onFail = Some(
      (resultOnScalaOptionsUnsupported, "Scala targets not supported by server")
    )
    register(server => server.buildTargetScalacOptions(params), onFail).asScala
  }

  def buildTargetSources(params: SourcesParams): Future[SourcesResult] = {
    register(server => server.buildTargetSources(params)).asScala
  }

  def buildTargetDependencySources(
      params: DependencySourcesParams
  ): Future[DependencySourcesResult] = {
    register(server => server.buildTargetDependencySources(params)).asScala
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

  private val cancelled = new AtomicBoolean(false)

  override def cancel(): Unit = {
    if (cancelled.compareAndSet(false, true)) {
      ongoingRequests.cancel()
      ongoingCompilations.cancel()
    }
  }

  def cancelCompilations(): Unit = {
    ongoingCompilations.cancel()
  }

  private def askUser(): Future[BuildServerConnection.LauncherConnection] = {
    if (config.askToReconnect) {
      if (!reconnectNotification.isDismissed) {
        val params = Messages.DisconnectedServer.params()
        languageClient.showMessageRequest(params).asScala.flatMap {
          case response if response == Messages.DisconnectedServer.reconnect =>
            reestablishConnection()
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
      reestablishConnection()
    }
  }

  private def reconnect(): Future[BuildServerConnection.LauncherConnection] = {
    val original = connection
    if (!isShuttingDown.get()) {
      synchronized {
        // if the future is different then the connection is already being reestablished
        if (connection eq original) {
          connection = askUser().map { conn =>
            // version can change when reconnecting
            _version.set(conn.version)
            ongoingRequests.addAll(conn.cancelables)
            conn.onConnectionFinished(reconnect)
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
      isCompile: Boolean = false,
  ): CompletableFuture[T] = {

    def runWithCanceling(
        launcherConnection: BuildServerConnection.LauncherConnection
    ): Future[T] = {
      val resultFuture = action(launcherConnection.server)
      val cancelable = Cancelable { () =>
        Try(resultFuture.cancel(true))
      }
      if (isCompile) ongoingCompilations.add(cancelable)
      else ongoingRequests.add(cancelable)

      val result = resultFuture.asScala

      result.onComplete { _ =>
        if (isCompile) ongoingCompilations.remove(cancelable)
        else ongoingRequests.remove(cancelable)
      }
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
          onFail
            .map { case (defaultResult, message) =>
              t match {
                case _: CancellationException =>
                  scribe.info(message)
                case _ =>
                  scribe.info(message, t)
              }
              Future.successful(defaultResult)
            }
            .getOrElse({
              val name = implicitly[ClassTag[T]].runtimeClass.getSimpleName
              Future.failed(new MetalsBspException(name, t))
            })
      }
    CancelTokens.future(_ => actionFuture)
  }

}

object BuildServerConnection {

  /**
   * Establishes a new build server connection with the given input/output streams.
   *
   * This method is blocking, doesn't return Future[], because if the `initialize` handshake
   * doesn't complete within a few seconds then something is wrong. We want to fail fast
   * when initialization is not successful.
   */
  def fromSockets(
      workspace: AbsolutePath,
      localClient: MetalsBuildClient,
      languageClient: LanguageClient,
      connect: () => Future[SocketConnection],
      reconnectNotification: DismissedNotifications#Notification,
      config: MetalsServerConfig,
      serverName: String,
      retry: Int = 5,
      supportsWrappedSources: Option[Boolean] = None,
  )(implicit
      ec: ExecutionContextExecutorService
  ): Future[BuildServerConnection] = {

    def setupServer(): Future[LauncherConnection] = {
      connect().map { case conn @ SocketConnection(_, output, input, _, _) =>
        val tracePrinter = Trace.setupTracePrinter("BSP", workspace)
        val launcher = new Launcher.Builder[MetalsBuildServer]()
          .traceMessages(tracePrinter.orNull)
          .setOutput(output)
          .setInput(input)
          .setLocalService(localClient)
          .setRemoteInterface(classOf[MetalsBuildServer])
          .setExecutorService(ec)
          .create()
        val listening = launcher.startListening()
        val server = launcher.getRemoteProxy
        val stopListening =
          Cancelable(() => listening.cancel(false))
        val result =
          try {
            BuildServerConnection.initialize(workspace, server, serverName)
          } catch {
            case e: TimeoutException =>
              conn.cancelables.foreach(_.cancel())
              stopListening.cancel()
              scribe.error("Timeout waiting for 'build/initialize' response")
              throw e
          }
        LauncherConnection(
          conn,
          server,
          result.getDisplayName(),
          stopListening,
          result.getVersion(),
          result.getCapabilities(),
        )
      }
    }

    setupServer()
      .map { connection =>
        new BuildServerConnection(
          setupServer,
          connection,
          languageClient,
          reconnectNotification,
          config,
          workspace,
          supportsWrappedSources.getOrElse(connection.supportsWrappedSources),
        )
      }
      .recoverWith { case e: TimeoutException =>
        if (retry > 0) {
          scribe.warn(s"Retrying connection to the build server $serverName")
          fromSockets(
            workspace,
            localClient,
            languageClient,
            connect,
            reconnectNotification,
            config,
            serverName,
            retry - 1,
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
  )

  /**
   * Run build/initialize handshake
   */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer,
      serverName: String,
  ): InitializeBuildResult = {
    val extraParams = BspExtraBuildParams(
      BuildInfo.javaSemanticdbVersion,
      BuildInfo.scalametaVersion,
      BuildInfo.supportedScala2Versions.asJava,
    )

    val initializeResult = server.buildInitialize {
      val params = new InitializeBuildParams(
        "Metals",
        BuildInfo.metalsVersion,
        BuildInfo.bspVersion,
        workspace.toURI.toString,
        new BuildClientCapabilities(
          List("scala", "java").asJava
        ),
      )
      val gson = new Gson
      val data = gson.toJsonTree(extraParams)
      params.setData(data)
      params
    }
    // Block on the `build/initialize` request because it should respond instantly
    // and we want to fail fast if the connection is not
    val result =
      if (serverName == SbtBuildTool.name) {
        initializeResult.get(60, TimeUnit.SECONDS)
      } else {
        initializeResult.get(20, TimeUnit.SECONDS)
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
  ) {

    def cancelables: List[Cancelable] =
      cancelServer :: socketConnection.cancelables

    def onConnectionFinished(
        f: () => Unit
    )(implicit ec: ExecutionContext): Unit = {
      socketConnection.finishedPromise.future.foreach(_ => f())
    }

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
