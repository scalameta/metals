package tests

import java.nio.file.Files
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.ExecuteClientCommandConfig
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.SlowTaskConfig
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.io.AbsolutePath

import munit.Ignore
import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.InitializeResult

/**
 * Full end to end integration tests against a full metals language server.
 */
abstract class BaseLspSuite(
    suiteName: String,
    initializer: BuildServerInitializer = QuickBuildInitializer,
) extends BaseSuite {
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
  var onStartCompilation: () => Unit = () => ()

  protected def initializationOptions: Option[InitializationOptions] = None

  private var useVirtualDocs = false
  protected val changeSpacesToDash = true

  protected def useVirtualDocuments = useVirtualDocs

  protected def mtagsResolver: MtagsResolver =
    new TestMtagsResolver

  override def afterAll(): Unit = {
    if (server != null) {
      server.server.cancelAll()
    }
    ex.shutdown()
    sh.shutdown()
  }

  def writeLayout(layout: String): Unit = {
    FileLayout.fromString(layout, workspace)
  }

  def initialize(
      layout: String,
      expectError: Boolean = false,
  ): Future[InitializeResult] = {
    Debug.printEnclosing()
    writeLayout(layout)
    initializer.initialize(workspace, server, client, layout, expectError)
  }

  def assertConnectedToBuildServer(
      expectedName: String
  )(implicit loc: Location): Unit = {
    val obtained = server.server.bspSession.get.mainConnection.name
    assertNoDiff(obtained, expectedName)
  }

  def test(
      testOpts: TestOptions,
      withoutVirtualDocs: Boolean,
      maxRetry: Int = 0,
  )(
      fn: => Future[Unit]
  )(implicit loc: Location): Unit = {
    def functionRetry(retry: Int): Future[Unit] = {
      fn.recoverWith {
        case _ if retry > 0 => functionRetry(retry - 1)
        case e => Future.failed(e)
      }
    }
    if (withoutVirtualDocs) {
      test(testOpts.withName(s"${testOpts.name}-readonly")) {
        functionRetry(maxRetry)
      }
      test(
        testOpts
          .withName(s"${testOpts.name}-virtualdoc")
          .withTags(Set(TestingServer.virtualDocTag))
      ) { functionRetry(maxRetry) }
    } else {
      test(testOpts)(functionRetry(maxRetry))
    }
  }

  def newServer(workspaceName: String): Unit = {
    workspace = createWorkspace(workspaceName)
    val buffers = Buffers()
    val config = serverConfig.copy(
      executeClientCommand = ExecuteClientCommandConfig.on,
      icons = this.icons,
    )

    val initOptions = initializationOptions
      .getOrElse(TestingServer.TestDefault)
      .copy(isVirtualDocumentSupported = Some(useVirtualDocs))

    client = new TestingClient(workspace, buffers)
    server = new TestingServer(
      workspace,
      client,
      buffers,
      config,
      bspGlobalDirectories,
      sh,
      time,
      initOptions,
      mtagsResolver,
      onStartCompilation,
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
    useVirtualDocs = context.test.tags.contains(TestingServer.virtualDocTag)
    newServer(context.test.name)
  }

  protected def createWorkspace(name: String): AbsolutePath = {
    val pathToSuite = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve(suiteName)

    val path =
      if (changeSpacesToDash)
        pathToSuite.resolve(name.replace(' ', '-'))
      else pathToSuite.resolve(name)

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
