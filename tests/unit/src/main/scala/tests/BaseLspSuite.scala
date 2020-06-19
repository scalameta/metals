package tests

import java.nio.file.Files
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ExecuteClientCommandConfig
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsLogger
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.SlowTaskConfig
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.Ignore
import munit.Location

/**
 * Full end to end integration tests against a full metals language server.
 */
abstract class BaseLspSuite(suiteName: String) extends BaseSuite {
  MetalsLogger.updateDefaultFormat()
  def icons: Icons = Icons.default
  def userConfig: UserConfiguration = UserConfiguration()
  def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(slowTask = SlowTaskConfig.on)
  def time: Time = Time.system
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private val sh = Executors.newSingleThreadScheduledExecutor()
  def bspGlobalDirectories: List[AbsolutePath] = Nil
  var server: TestingServer = _
  var client: TestingClient = _
  var workspace: AbsolutePath = _

  protected def initializationOptions: Option[InitializationOptions] = None

  override def afterAll(): Unit = {
    if (server != null) {
      server.server.cancelAll()
    }
    ex.shutdown()
    sh.shutdown()
  }

  def assertConnectedToBuildServer(
      expectedName: String
  )(implicit loc: Location): Unit = {
    val obtained = server.server.buildServer.get.name
    assertNoDiff(obtained, expectedName)
  }

  def newServer(workspaceName: String): Unit = {
    workspace = createWorkspace(workspaceName)
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
      initializationOptions
    )(ex)
    server.server.userConfig = this.userConfig
  }

  def cancelServer(): Unit = {
    if (server != null) {
      server.server.cancel()
    }
  }

  override def beforeEach(context: BeforeEach): Unit = {
    cancelServer()
    if (context.test.tags.contains(Ignore)) return
    newServer(context.test.name)
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

  def assertNoDiagnostics()(implicit
      loc: Location
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
    if (workspace.isDirectory) {
      try {
        RecursivelyDelete(workspace)
        Files.createDirectories(workspace.toNIO)
      } catch {
        case NonFatal(_) =>
          scribe.warn(s"Unable to delete workspace $workspace")
      }
    }
  }
}
