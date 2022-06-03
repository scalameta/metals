package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands

import org.eclipse.lsp4j.MessageActionItem

class ResetWorkspaceLspSuite extends BaseLspSuite(s"reset-workspace") {

  test("basic") {
    def bloopDir = server.workspace.resolve(".bloop")
    def classFileExists = bloopDir.exists && bloopDir.listRecursive.exists(f =>
      f.isFile && !f.filename.endsWith(".json")
    )
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |import scala.util.Success
          |import b.B
          |
          |object A {
          |  case class Foo(x: Int)
          |  case class Bar(y: String)
          |  val foo = Foo(B.x)
          |}
          |/a/src/main/scala/b/B.scala
          |package b
          |import scala.util.Success
          |object B {
          |  val x: Int = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        "",
      )
      _ = assert(classFileExists)
      _ = client.resetWorkspace =
        new MessageActionItem(Messages.ResetWorkspace.resetWorkspace)
      _ <- server.executeCommand(ServerCommands.ResetWorkspace)
      _ = assertNoDiff(
        client.workspaceShowMessages,
        "",
      )
      _ <- server.didSave("a/src/main/scala/b/B.scala")(
        _.replaceAll("val x: Int = 42", "val x: String = 42")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/b/B.scala:4:19: error: Found:    (42 : Int)
           |Required: String
           |  val x: String = 42
           |                  ^^
           |""".stripMargin,
      )
    } yield ()
  }
}
