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
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
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
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage

/**
 * An actively running and initialized BSP connection
 */
class BuildServerConnection private (
    setupConnection: () => Future[
      BuildServerConnection.LauncherConnection
    ],
    initialConnection: BuildServerConnection.LauncherConnection,
    localClient: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
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

  /**
   * Lifecycle of the underlying launcher connection, all transitions inside
   * `synchronized`. Invariants: at most one reconnect attempt in flight, and
   * only the attempt that commits back to `Connected` publishes side effects
   * (see scalameta/metals#3464).
   */
  private sealed trait LifecycleState
  private case class Connected(
      launcher: BuildServerConnection.LauncherConnection
  ) extends LifecycleState
  private case class Reconnecting(
      from: BuildServerConnection.LauncherConnection
  ) extends LifecycleState
  private case object Closed extends LifecycleState

  // all reads and writes are guarded by `synchronized`
  private var state: LifecycleState = Connected(initialConnection)

  private def reestablishConnection(
      lost: BuildServerConnection.LauncherConnection
  ) = {
    lost.optLivenessMonitor.foreach(_.shutdown())
    setupConnection()
  }

  val requestRegistry =
    new RequestRegistry(
      initialConnection.cancelables,
      languageClient,
      Some(requestTimeOutNotification),
    )

  private val isShuttingDown = new AtomicBoolean(false)

  /** Whether `shutdown()` has been initiated on this connection. */
  def shutdownInitiated: Boolean = isShuttingDown.get()

  /**
   * Runs `action` unless this connection is shutting down, atomically with
   * `shutdown()`: both take this connection's monitor, so publishing a
   * session can never resurrect one whose teardown has started
   * (see scalameta/metals#3464). Returns whether the action ran.
   */
  def unlessShutdown(action: () => Unit): Boolean =
    synchronized {
      if (shutdownInitiated) false
      else {
        action()
        true
      }
    }
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

  initialConnection.setReconnect(() => reconnect(initialConnection).ignoreValue)

  def isBloop: Boolean = name == BloopServers.name

  def isSbt: Boolean = name == SbtBuildTool.name

  def isMill: Boolean = name == MillBuildTool.bspName

  def isBazel: Boolean = name == BazelBuildTool.bspName

  def isScalaCLI: Boolean = ScalaCli.names(name)

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

  private val shutdownDone = Promise[Unit]()

  /**
   * Run build/shutdown procedure
   */
  def shutdown(): Future[Unit] = {
    // The close transition is atomic with reconnect admission (same monitor):
    // no reconnect can start between the flag and `Closed`, and the current
    // launcher's gate closes before the cleanup scan so a late `taskStart`
    // cannot slip in after it (see scalameta/metals#3464).
    val isFirst = synchronized {
      if (isShuttingDown.compareAndSet(false, true)) {
        // Tear the launcher down from the state machine, not from the
        // `connection` future: that future may be incomplete for an already
        // current launcher, or never complete while a reconnect prompt is
        // open, leaving the gate open and the liveness monitor scheduled.
        val closing = state match {
          case Connected(launcher) => Some(launcher)
          case Reconnecting(from) => Some(from)
          case Closed => None
        }
        closing.foreach { launcher =>
          launcher.dispatchGate.close()
          launcher.optLivenessMonitor.foreach(_.shutdown())
        }
        state = Closed
        true
      } else false
    }
    if (isFirst) {
      localClient.onConnectionClosed(this)
      shutdownDone.completeWith(remoteShutdown())
    }
    // duplicate calls join the in-flight shutdown, so no caller tears the
    // transport down while `build/shutdown` is still being delivered
    shutdownDone.future
  }

  private def remoteShutdown(): Future[Unit] =
    if (!connection.isCompleted) {
      // A reconnect attempt is in flight, waiting for it could hang the
      // disconnect indefinitely behind its prompt or setup. The attempt
      // observes `Closed` when it completes and tears its launcher down.
      cancel()
      Future.successful(())
    } else
      connection
        .map { conn =>
          try {
            conn.server.buildShutdown().get(2, TimeUnit.SECONDS)
            conn.server.onBuildExit()
            scribe.info("Shut down connection with build server.")
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
          } finally {
            // the drain window ends with the handshake, it must not stay
            // open indefinitely
            conn.dispatchGate.close()
            conn.optLivenessMonitor.foreach(_.shutdown())
            // Cancel pending compilations on our side, this is not needed for Bloop.
            cancel()
          }
        }
        // a failed connection future must still cancel and complete so the
        // caller's teardown is not skipped
        .recover { case NonFatal(_) => cancel() }

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
        restartByDefault = true,
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
        restartByDefault = true,
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

  private def jvmRunEnvironment[Env: ClassTag](
      isProvider: BuildServerConnection.LauncherConnection => Boolean,
      getEnv: MetalsBuildServer => CompletableFuture[Env],
      bspTargetName: String,
      empty: => Env,
  ): Future[Env] = {
    connection.flatMap { conn =>
      if (isProvider(conn)) {
        register(
          server => getEnv(server),
          onFail = Some(
            (
              empty,
              s"${name} should support `$bspTargetName`, but it fails.",
            )
          ),
        ).asScala
      } else {
        scribe.warn(
          s"${conn.displayName} does not support `$bspTargetName`, unable to fetch run environment."
        )
        Future.successful(empty)
      }
    }
  }

  def jvmRunEnvironment(
      params: JvmRunEnvironmentParams
  ): Future[JvmRunEnvironmentResult] = {
    jvmRunEnvironment(
      isProvider = _.capabilities.getJvmRunEnvironmentProvider,
      getEnv = _.buildTargetJvmRunEnvironment(params),
      bspTargetName = "buildTarget/jvmRunEnvironment",
      empty = new JvmRunEnvironmentResult(Collections.emptyList),
    )
  }

  def jvmTestEnvironment(
      params: JvmTestEnvironmentParams
  ): Future[JvmTestEnvironmentResult] = {
    jvmRunEnvironment(
      isProvider = _.capabilities.getJvmTestEnvironmentProvider,
      getEnv = _.buildTargetJvmTestEnvironment(params),
      bspTargetName = "buildTarget/jvmTestEnvironment",
      empty = new JvmTestEnvironmentResult(Collections.emptyList),
    )
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
    if (isSbt) Future.successful(resultOnJavacOptionsUnsupported)
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

  /**
   * On connection loss, reconnects or asks the user to. Returns `None` when
   * declined or dismissed. NOTE: no branch may return the mutable
   * `connection` field, which already holds the future built from this
   * result, so returning it would make that future wait on itself forever.
   */
  private def askUser(
      lost: BuildServerConnection.LauncherConnection
  ): Future[Option[BuildServerConnection.LauncherConnection]] = {
    if (config.askToReconnect) {
      if (!reconnectNotification.isDismissed) {
        val params = Messages.DisconnectedServer.params()
        languageClient
          .showMessageRequest(
            params,
            ConnectionProvider.ConnectRequestCancelationGroup,
          )
          .flatMap {
            case response
                if response == Messages.DisconnectedServer.reconnect =>
              reestablishConnection(lost).map(Some(_))
            case response if response == Messages.DisconnectedServer.notNow =>
              reconnectNotification.dismiss(5, TimeUnit.MINUTES)
              Future.successful(None)
            case _ =>
              Future.successful(None)
          }
      } else {
        Future.successful(None)
      }
    } else {
      reestablishConnection(lost).map(Some(_))
    }
  }

  private def reconnect(
      lost: BuildServerConnection.LauncherConnection
  ): Future[BuildServerConnection.LauncherConnection] =
    synchronized {
      state match {
        case Closed | Reconnecting(_) =>
          // shutting down, or an attempt is already in flight and its outcome
          // serves this trigger as well
          connection
        case Connected(current) if current ne lost =>
          // a trigger from a launcher that is no longer current, e.g. a
          // delayed failure of a request that ran on the previous connection
          connection
        case Connected(_) =>
          state = Reconnecting(lost)
          // `close()` serializes with dispatch, so afterwards no task
          // buffered on the dead socket can register a new compilation and
          // the scan below ends what this connection owns (metals#3464).
          lost.dispatchGate.close()
          localClient.onConnectionClosed(this)
          val attempt = askUser(lost)
          connection = attempt
            .map {
              case Some(newConn) => install(lost, newConn)
              case None =>
                // declined: the old, dead connection stays current, requests
                // on it fail fast and a later failure may ask again
                rollbackTo(lost)
                lost
            }
            .recover { case NonFatal(error) =>
              // only the attempt itself can fail, `install` never throws.
              // Falling back to the dead connection keeps `state` and
              // `connection` consistent and lets the next request trigger a
              // fresh attempt, so a transient setup failure is recoverable.
              scribe.error("Failed to reconnect to the build server", error)
              rollbackTo(lost)
              lost
            }
          connection
      }
    }

  /**
   * Atomically installs a reconnect attempt's launcher as the current
   * connection, all-or-nothing: on any internal failure the fresh launcher is
   * torn down and the lost connection is kept instead. Never throws and
   * returns the launcher that becomes the value of `connection`, so `state`
   * and the chosen transport cannot diverge.
   */
  private def install(
      lost: BuildServerConnection.LauncherConnection,
      newConn: BuildServerConnection.LauncherConnection,
  ): BuildServerConnection.LauncherConnection =
    synchronized {
      def tearDown(): Unit = {
        // each step isolated: one failure must not skip the others
        def attempt(step: => Unit): Unit =
          try step
          catch {
            case NonFatal(error) =>
              scribe.error("Failed to close a build server connection", error)
          }
        attempt(newConn.dispatchGate.close())
        // not part of `cancelables`, would keep pinging the dead launcher
        attempt(newConn.optLivenessMonitor.foreach(_.shutdown()))
        newConn.cancelables.foreach(cancelable => attempt(cancelable.cancel()))
      }
      state match {
        case Reconnecting(from) if from eq lost =>
          val installed =
            try {
              // version can change when reconnecting
              _version.set(newConn.version)
              requestRegistry.addOngoingRequest(newConn.cancelables)
              newConn.setReconnect(() => reconnect(newConn).ignoreValue)
              state = Connected(newConn)
              true
            } catch {
              case NonFatal(error) =>
                scribe.error(
                  "Failed to install the reconnected build server connection",
                  error,
                )
                tearDown()
                state = Connected(lost)
                false
            }
          if (installed) {
            notifyReconnected(newConn)
            newConn
          } else lost
        case _ =>
          // the connection was closed while the attempt was in flight
          scribe.info(
            "closing a build server connection that was replaced before it completed connecting"
          )
          tearDown()
          lost
      }
    }

  /**
   * Runs the `onReconnection` hook outside the transport-selection chain, so
   * its failure can never affect which connection is current. It runs only
   * if the installed launcher is still current at execution time, so it
   * cannot republish a session whose teardown or replacement has started.
   */
  private def notifyReconnected(
      installed: BuildServerConnection.LauncherConnection
  ): Unit =
    try {
      Future {
        val stillCurrent = synchronized {
          state match {
            case Connected(current) => current eq installed
            case _ => false
          }
        }
        if (stillCurrent) onReconnection.get()(this)
        else Future.successful(())
      }.flatten.onComplete {
        case Failure(error) =>
          scribe.error("The build server reconnection callback failed", error)
        case _ => ()
      }
    } catch {
      case NonFatal(error) =>
        scribe.error("The build server reconnection callback failed", error)
    }

  private def rollbackTo(
      lost: BuildServerConnection.LauncherConnection
  ): Unit =
    synchronized {
      state match {
        case Reconnecting(from) if from eq lost => state = Connected(lost)
        case _ =>
      }
    }

  private def register[T: ClassTag](
      action: MetalsBuildServer => CompletableFuture[T],
      onFail: => Option[(T, String)] = None,
      timeout: Option[Timeout] = None,
      restartByDefault: Boolean = false,
  ): CompletableFuture[T] =
    // sticky admission: a closed connection accepts no further requests, they
    // would perform stale remote work on the old transport
    if (isShuttingDown.get()) {
      onFail match {
        case Some((defaultResult, message)) =>
          scribe.info(message)
          CompletableFuture.completedFuture(defaultResult)
        case None =>
          CompletableFuture.failedFuture(
            new MetalsBspException(
              implicitly[ClassTag[T]].runtimeClass.getSimpleName,
              new IllegalStateException("build server connection is closed"),
            )
          )
      }
    } else registerWithOpenConnection(action, onFail, timeout, restartByDefault)

  private def registerWithOpenConnection[T: ClassTag](
      action: MetalsBuildServer => CompletableFuture[T],
      onFail: => Option[(T, String)],
      timeout: Option[Timeout],
      restartByDefault: Boolean,
  ): CompletableFuture[T] = {
    val localCancelable = new MutableCancelable()
    def runWithCanceling(
        launcherConnection: BuildServerConnection.LauncherConnection
    ): Future[T] = {
      val CancelableFuture(result, cancelable) = requestRegistry.register(
        action = () => action(launcherConnection.server),
        timeout = timeout,
        cancelByDefault = restartByDefault,
      )
      localCancelable.add(cancelable)
      result.onComplete(_ => localCancelable.remove(cancelable))
      result
    }

    val original = connection
    val actionFuture = original
      .flatMap { launcherConnection =>
        runWithCanceling(launcherConnection).recoverWith {
          case io: JsonRpcException if io.getCause.isInstanceOf[IOException] =>
            synchronized {
              reconnect(launcherConnection)
                .flatMap(conn => runWithCanceling(conn))
            }
        }
      }
      .recoverWith {
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
      languageClient: ConfiguredLanguageClient,
      connect: () => Future[SocketConnection],
      requestTimeOutNotification: DismissedNotifications#Notification,
      reconnectNotification: DismissedNotifications#Notification,
      config: MetalsServerConfig,
      userConfiguration: UserConfiguration,
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
        val dispatchGate = new LauncherDispatchGate
        val launcher =
          new Launcher.Builder[MetalsBuildServer]()
            .traceMessages(tracePrinter.orNull)
            .setOutput(output)
            .setInput(input)
            .setLocalService(localClient)
            .setRemoteInterface(classOf[MetalsBuildServer])
            .setExecutorService(ec)
            .wrapMessages { consumer =>
              val monitored = wrapper(consumer)
              (message: Message) => dispatchGate.dispatch(monitored, message)
            }
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
              userConfiguration,
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
          dispatchGate,
        )
      }
    }

    setupServer()
      .map { connection =>
        new BuildServerConnection(
          setupServer,
          connection,
          localClient,
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
            userConfiguration,
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

  final case class InitializeBuildData(
      enabledRules: Array[String]
  )

  /**
   * Run build/initialize handshake
   */
  private def initialize(
      workspace: AbsolutePath,
      server: MetalsBuildServer,
      serverName: String,
      config: MetalsServerConfig,
      userConfiguration: UserConfiguration,
  ): InitializeBuildResult = {
    val isBazel = serverName == BazelBuildTool.bspName
    val gson = new Gson
    val (data, dataKind) =
      if (isBazel)
        (
          gson.toJsonTree(
            InitializeBuildData(BazelBuildTool.enabledRules(workspace).toArray)
          ),
          "bazel-data-kind",
        )
      else
        (
          gson.toJsonTree(
            BspExtraBuildParams(
              BuildInfo.javaSemanticdbVersion,
              BuildInfo.scalametaVersion,
              BuildInfo.supportedScala2Versions.asJava,
              config.enableBestEffort || userConfiguration.enableBestEffort,
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
        workspace.toURI.toString,
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

  private case class LauncherConnection(
      socketConnection: SocketConnection,
      server: MetalsBuildServer,
      displayName: String,
      cancelServer: Cancelable,
      version: String,
      capabilities: BuildServerCapabilities,
      optLivenessMonitor: Option[ServerLivenessMonitor],
      dispatchGate: LauncherDispatchGate,
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

/**
 * Ties the launcher's incoming state-mutating notifications to its lifecycle:
 * once `close()` returns, none of them are dispatched anymore. A dead
 * generation can then neither create a "Compiling" progress that never
 * finishes, nor keep a replacement's progress alive, nor repopulate
 * diagnostics that teardown has just reset (see scalameta/metals#3464).
 * Requests, responses and outgoing notifications, e.g. `build/exit`, always
 * pass and bypass the monitor, so a blocked write can never delay them.
 */
final class LauncherDispatchGate {
  import LauncherDispatchGate._

  private var closed = false

  def close(): Unit = synchronized { closed = true }

  def dispatch(consumer: MessageConsumer, message: Message): Unit =
    message match {
      case notification: NotificationMessage
          if generationBound(notification.getMethod()) =>
        synchronized {
          if (!closed) consumer.consume(message)
          else
            scribe.debug(
              s"dropped ${notification.getMethod()} from a closed build server connection"
            )
        }
      case _ => consumer.consume(message)
    }
}

object LauncherDispatchGate {

  /**
   * Server-to-client notifications that mutate Metals state. Enumerated
   * rather than matched by prefix so that outgoing notifications, above all
   * `build/exit`, keep flowing while the connection is closing.
   */
  private val generationBound: Set[String] = Set(
    "build/taskStart", "build/taskFinish", "build/taskProgress",
    "build/publishDiagnostics", "build/logMessage", "build/showMessage",
    "buildTarget/didChange",
  )
}
