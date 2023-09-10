package tests

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

class WorkspaceFoldersSuite
    extends BaseCompletionLspSuite("workspaceFolderSuite") {
  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        Map(
          "testFolder" ->
            s"""|/metals.json
                |{"a":{"scalaVersion" : ${V.scala213}}}
                |/a/src/main/scala/a/A.scala
                |package a
                |case class MyObjectA()
                |""".stripMargin,
          "otherTestFolder" ->
            s"""|/metals.json
                |{"a":{"scalaVersion" : ${V.scala213}}}
                |/a/src/main/scala/a/B.scala
                |package a
                |case class MyObjectB()
                |/a/src/main/scala/a/C.scala
                |package a
                |object O {
                |  // @@
                |}
                |""".stripMargin,
        ),
        expectError = false,
      )
      _ = assert(server.fullServer.folderServices.length == 2)
      _ <- server.executeCommand(ServerCommands.ConnectBuildServer)
      _ = assertNoDiff(
        server.client.workspaceMessageRequests,
        "For which folder would you like to connect to build server?",
      )
      _ = assertNoDiff(
        server.client.workspaceShowMessages,
        "",
      )
      _ = assertNoDiff(
        server.workspaceSymbol("MyObject"),
        s"""|a.MyObjectA
            |a.MyObjectB
            |""".stripMargin,
      )
      _ <- server.didOpen("otherTestFolder/a/src/main/scala/a/B.scala")
      _ = assertCompletion(
        "val a = MyObjec@@",
        "MyObjectB a",
        filename = Some("otherTestFolder/a/src/main/scala/a/C.scala"),
      )
    } yield ()
  }
}
