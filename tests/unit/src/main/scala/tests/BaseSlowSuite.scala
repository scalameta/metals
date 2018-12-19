package tests
import java.nio.file.Files
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.BloopProtocol
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ExecuteClientCommandConfig
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.io.AbsolutePath

/**
 * Full end to end integration tests against a full metals language server.
 */
abstract class BaseSlowSuite(suiteName: String) extends BaseSuite {
  def protocol: BloopProtocol = BloopProtocol.auto
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  def bspGlobalDirectories: List[AbsolutePath] = Nil
  var server: TestingServer = _
  var client: TestingClient = _
  var workspace: AbsolutePath = _
  override def afterAll(): Unit = {
    if (server != null) {
      server.cancel()
    }
    ex.shutdown()
  }
  def assertConnectedToBuildServer(
      expectedName: String
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    val obtained = server.server.buildServer.get.name
    assertNoDiff(obtained, expectedName)
  }
  override def utestBeforeEach(path: Seq[String]): Unit = {
    if (server != null) {
      server.cancel()
    }
    val name = path.last
    if (utest.ufansi.Str(name).plainText.contains("IGNORED")) return
    workspace = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve(suiteName)
      .resolve(name.replace(' ', '-'))
    Files.createDirectories(workspace.toNIO)
    val buffers = Buffers()
    val config = MetalsServerConfig.default.copy(
      bloopProtocol = protocol,
      executeClientCommand = ExecuteClientCommandConfig.on
    )
    client = new TestingClient(workspace, buffers)
    server = new TestingServer(
      workspace,
      client,
      buffers,
      config,
      bspGlobalDirectories
    )(ex)
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
}
