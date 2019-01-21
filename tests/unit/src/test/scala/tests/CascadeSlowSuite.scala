package tests

import scala.meta.internal.metals.ServerCommands

object CascadeSlowSuite extends BaseSlowSuite("cascade") {
  testAsync("basic") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": { },
          |  "b": { "dependsOn": ["a"] },
          |  "c": { }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val n = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  val n: String = a.A.n
          |}
          |/c/src/main/scala/c/C.scala
          |package c
          |object C {
          |  val n: String = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ <- server.executeCommand(ServerCommands.CascadeCompile.id)
      // Check that cascade compile triggers compile in dependent project "b"
      // but not independent project "c".
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:19: error: type mismatch;
           | found   : Int
           | required: String
           |  val n: String = a.A.n
           |                  ^^^^^
          """.stripMargin
      )
    } yield ()
  }
}
