package tests

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import org.eclipse.lsp4j.MessageActionItem

class BspErrorHandlerSuite extends BaseTablesSuite {
  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  val client = new TestingClient(workspace, Buffers())
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
    val errorHandler = new TestBspErrorHandler(client, workspace, tables)

    FileLayout.fromString(
      s"""|/.metals/metals.log
          |
          |""".stripMargin,
      workspace,
    )

    client.bspError = new MessageActionItem("OK")

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
    tables: Tables,
)(implicit context: ExecutionContext)
    extends BspErrorHandler(
      languageClient,
      workspaceFolder,
      () => None,
      tables,
    ) {
  override def shouldShowBspError: Boolean = true

  override def logError(message: String): Unit =
    logsPath.appendText(s"$message\n")
}
