package tests

import scala.meta.internal.metals.ServerCommands

class CascadeLspSuite extends BaseLspSuite("cascade") {
  test("basic") {
    for {
      _ <- initialize(
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
      _ <- server.executeCommand(ServerCommands.CascadeCompile)
      // Check that cascade compile triggers compile in dependent project "b"
      // but not independent project "c".
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:19: error: Found:    (a.A.n : Int)
           |Required: String
           |  val n: String = a.A.n
           |                  ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
