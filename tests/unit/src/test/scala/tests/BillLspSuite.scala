package tests

import scala.concurrent.Future

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.ServerCommands
import scala.meta.io.AbsolutePath

import bill._
import ch.epfl.scala.bsp4j.StatusCode

class BillLspSuite extends BaseLspSuite("bill") {

  def globalBsp: AbsolutePath = workspace.resolve("global-bsp")
  override def bspGlobalDirectories: List[AbsolutePath] =
    List(globalBsp.resolve("bsp"))
  def testRoundtripCompilation(): Future[Unit] = {
    for {
      _ <- initialize(
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
        """.stripMargin,
      )
      _ <- server.didChange("src/com/App.scala")(_ => "object App")
      _ <- server.didSave("src/com/App.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        "",
      )
    } yield ()
  }

  test("diagnostics") {
    cleanWorkspace()
    Bill.installWorkspace(workspace)
    testRoundtripCompilation()
  }

  test("reconnect-manual") {
    cleanWorkspace()
    Bill.installWorkspace(workspace)
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
      _ <- server.executeCommand(ServerCommands.DisconnectBuildServer)
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = {
        val logs = workspace
          .resolve(Bill.logName)
          .readText
          .linesIterator
          .filter(_.startsWith("trace:"))
          .mkString("\n")
        assertNoDiff(
          logs,
          // Assert that we can manually shut downn the server, and then
          // manually connect back up with no issues.
          """|trace: initialize
             |trace: shutdown
             |trace: initialize
             |""".stripMargin,
        )
      }
    } yield ()
  }

  test("reconnect") {
    cleanWorkspace()
    Bill.installWorkspace(workspace)
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
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = {
        val logs = workspace
          .resolve(Bill.logName)
          .readText
          .linesIterator
          .filter(_.startsWith("trace:"))
          .mkString("\n")
        assertNoDiff(
          logs,
          // Assert that "Connect to build server" waits for the shutdown
          // response from the build server before sending "initialize".
          // Essentially the same from above without needing to manually
          // disconnect first
          """|trace: initialize
             |trace: shutdown
             |trace: initialize
             |trace: shutdown
             |trace: initialize
             |""".stripMargin,
        )
      }
    } yield ()
  }

  test("automatic-reconnect".flaky) {
    cleanWorkspace()
    Bill.installWorkspace(workspace)
    for {
      _ <- initialize(
        """
          |/src/com/App.scala
          |object App {
          |  val x: Int = ""
          |}
          |/first-shutdown-timeout
          |2000
          |/shutdown-trace
          |true
        """.stripMargin
      )
      _ <- Future { Thread.sleep(3000) }
      _ <- server.didOpen("src/com/App.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |src/com/App.scala:2:16: error: type mismatch;
          | found   : String("")
          | required: Int
          |  val x: Int = ""
          |               ^^
        """.stripMargin,
      )
      _ <- server.didChange("src/com/App.scala")(_ => "object App")
      _ <- server.didSave("src/com/App.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        "",
      )
      _ = {
        val logs = workspace
          .resolve(Directories.log)
          .readText
          .linesIterator
          .filter(_.startsWith("trace:"))
          .mkString("\n")
        assertNoDiff(
          logs,
          // Assert that "Connect to build server" waits for the shutdown
          // response from the build server before sending "initialize".
          """|trace: initialize
             |trace: initialize
             |""".stripMargin,
        )
      }
    } yield ()
  }

  test("global") {
    RecursivelyDelete(globalBsp)
    cleanWorkspace()
    Bill.installGlobal(globalBsp)
    testRoundtripCompilation()
  }

  def testSelectServerDialogue(): Future[Unit] = {
    // when asked, choose the Bob build tool
    client.selectBspServer = { actions =>
      actions.find(_.getTitle == "Bob").get
    }
    for {
      _ <- initialize(
        """
          |/src/App.scala
          |object App {}
        """.stripMargin
      )
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        List(
          Messages.BspSwitch.message,
          Messages.CheckDoctor.allProjectsMisconfigured,
        ).mkString("\n"),
      )
    } yield ()
  }

  test("conflict") {
    cleanWorkspace()
    Bill.installWorkspace(workspace, "Bill")
    Bill.installWorkspace(workspace, "Bob")
    testSelectServerDialogue()
  }

  test("mix") {
    cleanWorkspace()
    RecursivelyDelete(globalBsp)
    Bill.installWorkspace(workspace, "Bill")
    Bill.installGlobal(globalBsp, "Bob")
    testSelectServerDialogue()
  }

  test("cancel-compile") {
    val cancelPattern =
      """Sending notification '\$\/cancelRequest'\s*Params: \{\s*\"id\": \"([0-9]+)\"\s*\}""".r
    cleanWorkspace()
    Bill.installWorkspace(workspace, "Bill")
    def trace = workspace.resolve(".metals/bsp.trace.json").readText
    for {
      _ <- initialize(
        """|/src/com/App.scala
           |object App {
           |  val x: Int = 1
           |}
           |/.metals/bsp.trace.json
           |""".stripMargin
      )
      (compileReport, _) <- server.server.compilations
        .compileFile(workspace.resolve("src/com/App.scala"))
        .zip {
          // wait until the compilation start
          while (!trace.contains(s"buildTarget/compile")) {
            Thread.sleep(1)
          }
          server.executeCommand(ServerCommands.CancelCompile)
        }
      _ = assertEquals(compileReport.getStatusCode(), StatusCode.CANCELLED)
      // wait for all the side effect (`onComplete`) actions of cancellation to happen
      _ = Thread.sleep(1000)
      currentTrace = trace
      cancelMatch = cancelPattern.findFirstMatchIn(currentTrace)
      _ = assert(cancelMatch.nonEmpty, trace)
      cancelId = cancelMatch.get.group(1)
      _ = assert(currentTrace.contains(s"buildTarget/compile - ($cancelId)"))
      compileReport <- server.server.compilations
        .compileFile(workspace.resolve("src/com/App.scala"))
      _ = assertEquals(compileReport.getStatusCode(), StatusCode.OK)
    } yield ()
  }
}
