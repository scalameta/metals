package tests

import scala.meta.internal.metals.ServerCommands

class ResetWorkspaceLspSuite extends BaseLspSuite(s"clean-all") {

  test("basic") {
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
          |object A {
          |  val x: String = 42
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
      _ <- server.executeCommand(ServerCommands.ResetWorkspace)
      _ = assertNoDiff(
        client.workspaceShowMessages,
        "",
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:4:19: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val x: String = 42
           |                  ^^
           |""".stripMargin,
      )

    } yield ()
  }
}
