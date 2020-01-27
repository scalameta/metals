package tests.build

import scala.meta.internal.metals.ServerCommands
import tests.BaseLspSuite

class BuildServerConnectionLspSuite
    extends BaseLspSuite("build-server-connection") {
  test("basic".flaky) {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val n = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ = server.server.buildServer.get.cancel()
      _ = assertNoDiagnostics()
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer.id)
      _ <- server.didSave("a/src/main/scala/a/A.scala")(
        _.replaceAllLiterally("val n = 42", "val n: String = 42")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:3:19: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val n: String = 42
           |                  ^^
           |""".stripMargin
      )
    } yield ()
  }

}
