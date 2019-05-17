package tests.debug

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import tests.BaseSuite
import tests.FileLayout
import tests.QuickBuild
import scala.concurrent.ExecutionContext.fromExecutorService
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.NoopLanguageClient
import scala.meta.io.AbsolutePath

abstract class BaseDebugSuite extends BaseSuite {
  private val executor: ExecutorService = Executors.newCachedThreadPool()
  protected implicit val executionContext: ExecutionContextExecutorService =
    fromExecutorService(executor)
  private var metalsServer: MetalsLanguageServer = _

  protected var workspace: AbsolutePath = _
  protected var server: TestingDebugServer = _
  protected var client: TestingDebugClient = _

  override def utestBeforeEach(path: Seq[String]): Unit = {
    if (path.isEmpty) return
    workspace = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve("debug-adapter")
      .resolve(path.last.replace(' ', '-'))

    Files.createDirectories(workspace.toNIO)

    metalsServer = new MetalsLanguageServer(executionContext)
    metalsServer.connectToLanguageClient(NoopLanguageClient)

    client = new TestingDebugClient
    server = TestingDebugServer(
      workspace,
      metalsServer.compilations,
      metalsServer.buildTargets
    )
    server.setClient(client)
  }

  protected final def testDebug(name: String)(
      layout: String,
      setUp: InitializeRequestArguments => Unit = _ => {},
      act: TestingDebugServer => Future[_],
      assert: (Int, TestingDebugClient) => Unit
  ): Unit =
    testAsync(name) {
      initializeMetals(layout)

      val arguments = new InitializeRequestArguments()
      setUp(arguments)

      for {
        _ <- server.initialize(arguments)
        _ <- act(server)
        exitCode <- client.sessionTerminated()
      } yield assert(exitCode, client)
    }

  private def initializeMetals(
      layout: String
  ): Unit = {
    FileLayout.fromString(layout, workspace)
    QuickBuild.bloopInstall(workspace)

    val params = new InitializeParams()
    params.setRootUri(workspace.toURI.toString)

    val init = for {
      _ <- metalsServer.initialize(params).asScala
      _ <- metalsServer.initialized(new InitializedParams).asScala
      _ = require(metalsServer.buildServer.isDefined, "Metals not initialized")
    } yield ()

    init.asJava.get(10, TimeUnit.SECONDS)
  }

}
