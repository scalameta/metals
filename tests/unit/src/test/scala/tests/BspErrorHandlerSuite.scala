package tests

import java.nio.file.Paths
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject

class BspErrorHandlerSuite extends BaseSuite {
  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))
    .resolve("target/bsp-error-suite/")
    .createDirectories()
  val client = new TestingClient(workspace, Buffers())
  val errorHandler = new TestBspErrorHandler(client, workspace)
  val exampleError1 = "an error"
  val exampleError2 = "a different error"
  val longError: String =
    """|This is a long error.
       |Such a long error will not fully fit in the
       |message box to be show to the user.
       |It will get trimmed to maximum 150 characters.
       |The full message will be available in the metals logs.
       |""".stripMargin

  test("handle-bsp-error") {
    FileLayout.fromString(
      s"""|/.metals/metals.log
          |
          |""".stripMargin,
      workspace,
    )

    client.bspError = BspErrorHandler.restartBuildServer

    for {
      _ <- errorHandler.onError(exampleError1)
      _ <- errorHandler.onError(exampleError1)
      _ <- errorHandler.onError(exampleError2)
      _ = client.bspError = BspErrorHandler.dismiss
      _ <- errorHandler.onError(exampleError1)
      _ = client.bspError = BspErrorHandler.goToLogs
      _ <- errorHandler.onError(longError)
      _ <- errorHandler.onError(exampleError1)
      _ = client.bspError = BspErrorHandler.doNotShowErrors
      _ <- errorHandler.onError(exampleError2)
      _ <- errorHandler.onError(longError)
      _ <- errorHandler.onError(exampleError2)
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        s"""|${BspErrorHandler.makeShortMessage(exampleError1)}
            |${BspErrorHandler.makeShortMessage(exampleError2)}
            |${BspErrorHandler.makeShortMessage(exampleError1)}
            |${BspErrorHandler.makeLongMessage(longError)}
            |${BspErrorHandler.makeShortMessage(exampleError2)}
            |""".stripMargin,
      )
      assert(client.clientCommands.asScala.nonEmpty)
      assertNoDiff(
        client.clientCommands.asScala.head.getCommand(),
        "metals-goto-location",
      )
      val params = client.clientCommands.asScala.head
        .getArguments()
        .asScala
        .head
        .asInstanceOf[JsonObject]
      assertEquals(
        params.get("uri").getAsString(),
        workspace.resolve(Directories.log).toURI.toString(),
      )
      assertEquals(
        params
          .getAsJsonObject("range")
          .getAsJsonObject("start")
          .get("line")
          .getAsInt(),
        4,
      )
      workspace.resolve(Directories.log).delete()
    }
  }

}

class TestBspErrorHandler(
    val languageClient: TestingClient,
    workspaceFolder: AbsolutePath,
)(implicit context: ExecutionContext)
    extends BspErrorHandler(
      languageClient,
      workspaceFolder,
      () => Future.successful(true),
      () => None,
    ) {
  override def shouldShowBspError: Boolean = true

  override def logError(message: String): Unit =
    logsPath.appendText(s"$message\n")
}
