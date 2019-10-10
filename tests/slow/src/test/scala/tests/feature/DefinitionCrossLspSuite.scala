package tests.feature

import tests.BaseCompletionLspSuite
import scala.meta.internal.metals.BuildInfo

object DefinitionCrossLspSuite
    extends BaseCompletionLspSuite("definition-cross") {

  testAsync("2.11") {
    basicDefinitionTest(BuildInfo.scala211)
  }

  testAsync("2.13") {
    basicDefinitionTest(BuildInfo.scala213)
  }

  def basicDefinitionTest(scalaVersion: String) = {
    cleanDatabase()
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${scalaVersion}"
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
