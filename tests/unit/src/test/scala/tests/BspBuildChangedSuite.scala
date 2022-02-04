package tests

import scala.concurrent.Promise

import scala.meta.internal.metals.MetalsEnrichments._

import bill.Bill

class BspBuildChangedSuite extends BaseLspSuite("bsp-build-changed") {

  test("build changed") {
    cleanWorkspace()
    Bill.installWorkspace(workspace.toNIO)
    for {
      _ <- initialize(
        """
          |/src/com/App.scala
          |object App {
          |  val x: Int = ""
          |}
          |/shutdown-trace
          |true
        """.stripMargin
      )
      _ <- server.didOpen("src/com/App.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |src/com/App.scala:2:16: error: type mismatch;
          | found   : String("")
          | required: Int
          |  val x: Int = ""
          |               ^^
        """.stripMargin
      )
      _ = {
        server.server.buildServerPromise = Promise()
      }
      // workspaceReload on Bill triggers a buildTarget/didChange,
      // which should trigger a build server disconnection / reconnection
      _ <- server.server.bspSession.get.workspaceReload()
      _ <- server.server.buildServerPromise.future
      _ = {
        val logs = workspace
          .resolve(Bill.logName)
          .readText
          .linesIterator
          .filter(_.startsWith("trace:"))
          .mkString("\n")
        assertNoDiff(
          logs,
          """|trace: initialize
             |trace: shutdown
             |trace: initialize
             |""".stripMargin
        )
      }
    } yield ()
  }

}
