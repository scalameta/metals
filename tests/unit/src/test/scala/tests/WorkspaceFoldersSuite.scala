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

  // Ignored: test hangs indefinitely
  test("non-scala-project".ignore) {
    cleanWorkspace()
    val newScalaFile = "/a/src/main/scala/A.scala"
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
          "notYetScalaProject" ->
            s"""|/README.md
                |Will be a Scala project.
                |""".stripMargin,
        ),
        expectError = false,
      )
      _ = assert(server.fullServer.folderServices.length == 1)
      _ = assert(server.fullServer.nonScalaProjects.length == 1)
      _ = writeLayout(
        s"""|$newScalaFile
            |//> using scala ${V.scala213}
            |package a
            |object O {
            | val i: Int = "aaa"
            |}
            |""".stripMargin,
        "notYetScalaProject",
      )
      _ <- server.didOpen(s"notYetScalaProject$newScalaFile")
      _ = assert(server.fullServer.folderServices.length == 2)
      _ = assertNoDiff(
        server.client.pathDiagnostics(s"notYetScalaProject$newScalaFile"),
        s"""|notYetScalaProject/a/src/main/scala/A.scala:4:15: error: type mismatch;
            | found   : String("aaa")
            | required: Int
            | val i: Int = "aaa"
            |              ^^^^^
            |""".stripMargin,
      )
    } yield ()
  }
}
