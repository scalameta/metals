package tests

import bill._
import scala.concurrent.Future
import scala.meta.internal.metals.Messages
import scala.meta.io.AbsolutePath

object BillSlowSuite extends BaseSlowSuite("bill") {

  def globalBsp: AbsolutePath = workspace.resolve("global-bsp")
  override def bspGlobalDirectories: List[AbsolutePath] =
    List(globalBsp.resolve("bsp"))

  def testRoundtripCompilation(): Future[Unit] = {
    for {
      _ <- server.initialize(
        """
          |/src/com/App.scala
          |object App {
          |  val x: Int = ""
          |}
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
      _ <- server.didSave("src/com/App.scala")(_ => "object App")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        ""
      )
    } yield ()
  }

  testAsync("diagnostics") {
    Bill.installWorkspace(workspace.toNIO)
    testRoundtripCompilation()
  }

  testAsync("global") {
    Bill.installGlobal(globalBsp.toNIO)
    testRoundtripCompilation()
  }

  def testSelectServerDialogue(): Future[Unit] = {
    for {
      _ <- server.initialize(
        """
          |/src/App.scala
          |object App {}
        """.stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          Messages.SelectBspServer.message,
          Messages.CheckDoctor.allProjectsMisconfigured
        ).mkString("\n")
      )
    } yield ()
  }

  testAsync("conflict") {
    cleanDatabase()
    Bill.installWorkspace(workspace.toNIO, "Bill")
    Bill.installWorkspace(workspace.toNIO, "Bob")
    testSelectServerDialogue()
  }

  testAsync("mix") {
    cleanDatabase()
    Bill.installWorkspace(workspace.toNIO, "Bill")
    Bill.installGlobal(globalBsp.toNIO, "Bob")
    testSelectServerDialogue()
  }

}
