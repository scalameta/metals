package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ServerCommands

class BuildServerConnectionLspSuite
    extends BaseLspSuite("build-server-connection") {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(askToRestartBloop = true)

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
      _ = server.server.bspSession.get.cancel()
      _ = assertNoDiagnostics()
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("val n = 42", "val n: String = 42")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:3:19: error: type mismatch;
           | found   : Int(42)
           | required: String
           |  val n: String = 42
           |                  ^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("bloop-version-change") {
    cleanWorkspace()
    val updatedBloopVersion = "2.0.0-RC1-2-62717f42-SNAPSHOT"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":
            |  {}
            |}""".stripMargin
      )
      _ = assertEmpty(
        client.workspaceMessageRequests
      )
      _ <- server.didChangeConfiguration(
        s"""|{
            |  "bloopVersion": "${scala.meta.internal.metals.BuildInfo.bloopVersion}"
            |}
            |""".stripMargin
      )

      _ = assertEmpty(
        client.workspaceMessageRequests
      )
      _ <- server.didChangeConfiguration(
        s"""|{
            |  "bloopVersion": "$updatedBloopVersion"
            |}
            |""".stripMargin
      )
    } yield {
      assertNoDiff(
        client.workspaceMessageRequests,
        List(Messages.BloopVersionChange.params().getMessage()).mkString("\n"),
      )
    }
  }
}
