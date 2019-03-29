package tests

object DefinitionCrossSlowSuite
    extends BaseCompletionSlowSuite("definition-cross") {
  testAsync("2.11") {
    cleanDatabase()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {
          |    "scalaVersion": "2.11.12"
          |  }
          |}
          |/a/src/main/scala/a/Main.scala
          |object Main {
          |  println("hello!")
          |}
          |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions // trigger definition
      _ <- server.didOpen("scala/Predef.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        ""
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }
}
