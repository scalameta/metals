package tests

import java.nio.file.Paths
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

abstract class BaseManualSuite extends munit.FunSuite {
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

  def inDirectory(
      path: String,
      config: MetalsServerConfig = defaultMetalsServerConfig,
      userConfig: UserConfiguration = defaultUserConfig,
      removeCache: Boolean = false,
      onSetup: AbsolutePath => Unit = _ => (),
  ): FunFixture[(TestingServer, TestingClient)] =
    FunFixture.async[(TestingServer, TestingClient)](
      setup = { test =>
        val buffers = Buffers()
        val workspace = AbsolutePath(path)
        onSetup(workspace)
        if (removeCache) {
          workspace.resolve(".metals").deleteRecursively()
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
          mtagsResolver = new TestMtagsResolver(checkCoursier = true),
          onStartCompilation = () => (),
        )(ex)
        for {
          _ <- server.initialize()
          _ <- server.initialized()
          _ <- server.didChangeConfiguration(userConfig.toString)
        } yield {
          server.assertBuildServerConnection()
          (server, client)
        }
      },
      teardown = { case (server, _) =>
        Future.fromTry(Try(server.languageServer.cancel()))
      },
    )
}
