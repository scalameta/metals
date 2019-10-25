package tests
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientExperimentalCapabilities
import scala.meta.internal.metals.ExecuteClientCommandConfig
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

/**
 * Full end to end integration tests against a full metals language server.
 */
abstract class BaseLspSuite(suiteName: String) extends BaseSuite {
  MetalsLogger.updateDefaultFormat()
  def icons: Icons = Icons.default
  def userConfig: UserConfiguration = UserConfiguration()
  def serverConfig: MetalsServerConfig = MetalsServerConfig.default
  def time: Time = Time.system
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val sh = Executors.newSingleThreadScheduledExecutor()
  def bspGlobalDirectories: List[AbsolutePath] = Nil
  var server: TestingServer = _
  var client: TestingClient = _
  var workspace: AbsolutePath = _

  protected def experimentalCapabilities
      : Option[ClientExperimentalCapabilities] = None

  override def afterAll(): Unit = {
    if (server != null) {
      server.server.cancelAll()
    }
    ex.shutdown()
    sh.shutdown()
  }

  def assertConnectedToBuildServer(
      expectedName: String
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val obtained = server.server.buildServer.get.name
    assertNoDiff(obtained, expectedName)
  }

  override def utestBeforeEach(path: Seq[String]): Unit = {
    if (path.isEmpty) return
    if (server != null) {
      server.server.cancel()
    }
    val name = path.last
    if (utest.ufansi.Str(name).plainText.contains("IGNORED")) return
    workspace = createWorkspace(name)
    val buffers = Buffers()
    val config = serverConfig.copy(
      executeClientCommand = ExecuteClientCommandConfig.on,
      icons = this.icons
    )
    client = new TestingClient(workspace, buffers)
    server = new TestingServer(
      workspace,
      client,
      buffers,
      config,
      bspGlobalDirectories,
      sh,
      time,
      experimentalCapabilities
    )(ex)
    server.server.userConfig = this.userConfig
  }

  protected def createWorkspace(name: String): AbsolutePath = {
    val path = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve(suiteName)
      .resolve(name.replace(' ', '-'))

    Files.createDirectories(path.toNIO)
    path
  }

  def assertNoDiagnostics()(
      implicit file: sourcecode.File,
      line: sourcecode.Line
  ): Unit = {
    assertNoDiff(client.workspaceDiagnostics, "")
  }

  def cleanCompileCache(project: String): Unit = {
    RecursivelyDelete(workspace.resolve(".bloop").resolve(project))
  }
  def cleanDatabase(): Unit = {
    RecursivelyDelete(workspace.resolve(".metals").resolve("metals.h2.db"))
  }
  def cleanWorkspace(): Unit = {
    RecursivelyDelete(workspace)
    Files.createDirectories(workspace.toNIO)
  }

  def flakyTest(name: String, maxRetries: Int = 3)(
      run: => Future[Unit]
  ): Unit = {
    testAsync(name) {
      def loop(n: Int): Future[Unit] = {
        run.recoverWith {
          case NonFatal(_) if n > 0 =>
            scribe.info(s"test retry: $name")
            loop(n - 1)
        }
      }
      loop(maxRetries)
    }
  }
}
