package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.StatusBarConfig
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.io.AbsolutePath

import bill.Bill
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

class BspStatusSuite extends BaseLspSuite("bsp-status-suite") {

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(bspStatusBar = StatusBarConfig.on)

  test("basic") {
    val client = new StatusClient
    val bspStatus = new BspStatus(client, isBspStatusProvider = true)
    val folder1 = AbsolutePath(Paths.get(".")).resolve("folder1")
    val folder2 = AbsolutePath(Paths.get(".")).resolve("folder2")
    bspStatus.status(folder1, new MetalsStatusParams("some text"))
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("other text"))
    assertEquals(client.status, "other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("some other other text"))
    assertEquals(client.status, "some text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some text")
    bspStatus.focus(folder2)
    assertEquals(client.status, "some other other text")
  }

  test("no-bsp-status") {
    val client = new StatusClient
    val bspStatus = new BspStatus(client, isBspStatusProvider = false)
    val folder1 = AbsolutePath(Paths.get(".")).resolve("folder1")
    val folder2 = AbsolutePath(Paths.get(".")).resolve("folder2")
    bspStatus.status(folder1, new MetalsStatusParams("some text"))
    assertEquals(client.status, "some text")
    bspStatus.status(folder2, new MetalsStatusParams("other text"))
    assertEquals(client.status, "other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "other text")
    bspStatus.status(folder2, new MetalsStatusParams("some other other text"))
    assertEquals(client.status, "some other other text")
    bspStatus.focus(folder1)
    assertEquals(client.status, "some other other text")
  }

  test("bsp-error") {
    cleanWorkspace()
    Bill.installWorkspace(workspace.resolve("billWorkspace").toNIO, "Bill")
    def bloopReports = server.server.reports.bloop.getReports()
    for {
      _ <- initialize(
        Map(
          "bloopWorkspace" ->
            """|/metals.json
               |{ "a": { } }
               |
               |/a/src/main/scala/Main.scala
               |object Main {
               |  val x: Int = 1
               |}
               |""".stripMargin,
          "billWorkspace" ->
            """|/src/com/App.scala
               |object App {
               |  val x: Int = 1
               |}
               |""".stripMargin,
        ),
        expectError = false,
      )
      _ <- server.didOpen("bloopWorkspace/a/src/main/scala/Main.scala")
      _ = assertNoDiff(client.pollStatusBar(), s"Bloop ${Icons.default.link}")
      _ = client.statusParams.clear()
      _ <- server.didOpen("billWorkspace/src/com/App.scala")
      _ = assertNoDiff(client.pollStatusBar(), s"Bill ${Icons.default.link}")
      _ = client.statusParams.clear()
      _ = server.server.buildClient.onBuildLogMessage(
        new MessageParams(MessageType.Error, "This is an error.")
      )
      _ = assert(client.statusParams.isEmpty())
      _ <- server.didFocus("bloopWorkspace/a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.pollStatusBar(),
        s"Bloop 1 ${Icons.default.alert}",
      )
      reports = bloopReports
      _ = assert(!reports.isEmpty)
      reportUri = reports.get(0).file.toURI().toString
      newPath = (reportUri ++ ".seen").toAbsolutePath.toNIO
      _ = Files.move(reports.get(0).toPath, newPath)
      _ <- server.fullServer
        .didChangeWatchedFiles(
          new DidChangeWatchedFilesParams(
            List(new FileEvent(reportUri, FileChangeType.Deleted)).asJava
          )
        )
        .asScala
      _ = assertNoDiff(client.pollStatusBar(), s"Bloop ${Icons.default.link}")
      _ = assert(!bloopReports.isEmpty)
    } yield ()
  }

}

class StatusClient extends NoopLanguageClient {

  var status: String = ""

  override def metalsStatus(params: MetalsStatusParams): Unit =
    status = params.text
}
