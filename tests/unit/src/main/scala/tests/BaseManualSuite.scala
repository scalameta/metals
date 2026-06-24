package tests

import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.Testing
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

abstract class BaseManualSuite extends munit.FunSuite {
  Testing.enable()
  Testing.disableFileWatching()

  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val sh = Executors.newSingleThreadScheduledExecutor()
  override def afterAll(): Unit = {
    // sh.shutdownNow()
    // ex.shutdownNow()
  }
  override def munitTimeout: Duration = Duration("10min")
  override def munitExecutionContext: ExecutionContext = ex

  def preferredBuildServer: Option[String] = Some("bazelbsp")
  def bspGlobalDirectories: List[AbsolutePath] = List(
    AbsolutePath(
      Paths.get(System.getProperty("user.home"), ".local", "share", "bsp")
    )
  )
  def clientName: String = "Visual Studio Code"
  def awaitInitialized: Boolean = true
  def buildServerConnectionTimeout: Duration = Duration("2min")
  def mtagsResolver: MtagsResolver = new TestMtagsResolver(checkCoursier = true)

  def defaultUserConfig: UserConfiguration = UserConfiguration.default.copy(
    preferredBuildServer = preferredBuildServer,
    buildOnChange = false,
    buildOnFocus = false,
    presentationCompilerDiagnostics = true,
  )

  def defaultMetalsServerConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(
      statistics = StatisticsConfig.all
    )

  protected def manualLog(message: String): Unit =
    System.err.println(s"[ManualSuite ${Instant.now()}] $message")

  protected def logged[A](message: String)(future: => Future[A]): Future[A] = {
    manualLog(s"start: $message")
    future.transform { result =>
      result.fold(
        error => manualLog(s"failed: $message: ${error.getMessage}"),
        _ => manualLog(s"done: $message"),
      )
      result
    }(ex)
  }

  private def waitForBuildServerConnection(
      server: TestingServer
  ): Future[Unit] = Future {
    val deadline = System.nanoTime() + buildServerConnectionTimeout.toNanos
    var last: Option[Throwable] = None
    var connected = false
    while (!connected && System.nanoTime() < deadline) {
      try {
        server.assertBuildServerConnection()
        connected = true
      } catch {
        case NonFatal(error) =>
          last = Some(error)
          if (System.nanoTime() < deadline) {
            Thread.sleep(1000)
          }
      }
    }
    if (!connected) {
      last.foreach(throw _)
      server.assertBuildServerConnection()
    }
  }(ex)

  def inDirectory(
      path: String,
      config: MetalsServerConfig = defaultMetalsServerConfig,
      userConfig: UserConfiguration = defaultUserConfig,
      removeCache: Boolean = false,
      removeIndexMbt: Boolean = false,
      onSetup: AbsolutePath => Unit = _ => (),
  ): FunFixture[(TestingServer, TestingClient)] =
    FunFixture.async[(TestingServer, TestingClient)](
      setup = { test =>
        val buffers = Buffers()
        val workspace = AbsolutePath(path)
        manualLog(s"setup ${test.name} in $workspace")
        onSetup(workspace)
        manualLog("setupBsp completed")
        if (removeCache) {
          workspace.resolve(".metals").deleteRecursively()
          manualLog("removed .metals")
        }
        if (removeIndexMbt) {
          val indexMbt = workspace.resolve(".metals").resolve("index.mbt")
          if (indexMbt.exists) indexMbt.delete()
          manualLog("removed .metals/index.mbt")
        }
        val client = new TestingClient(workspace, buffers)
        val server = new TestingServer(
          workspace = workspace,
          client = client,
          buffers = buffers,
          config = config,
          initialUserConfig = userConfig,
          bspGlobalDirectories = bspGlobalDirectories,
          sh = sh,
          time = Time.system,
          initializationOptions = TestingServer.TestDefault
            .copy(isVirtualDocumentSupported = Some(true)),
          mtagsResolver = mtagsResolver,
          clientName = clientName,
          onStartCompilation = () => (),
        )(ex)
        for {
          _ <- logged("server.initialize")(server.initialize())
          _ <-
            if (awaitInitialized) {
              logged("server.initialized")(server.initialized())
            } else {
              manualLog("send: server.initialized")
              server.initialized()
              manualLog("sent: server.initialized")
              Future.unit
            }
          _ <- logged("server.didChangeConfiguration")(
            server.didChangeConfiguration(userConfig.toString)
          )
          _ <- logged("build server connection")(
            waitForBuildServerConnection(server)
          )
        } yield {
          (server, client)
        }
      },
      teardown = { case (server, _) =>
        Future.fromTry(Try(server.languageServer.cancel()))
      },
    )
}
