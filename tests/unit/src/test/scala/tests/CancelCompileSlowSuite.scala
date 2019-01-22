package tests

import java.util.concurrent.CancellationException
import scala.meta.internal.metals.ServerCommands

object CancelCompileSlowSuite extends BaseSlowSuite("compile-cancel") {

  testAsync("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": {"dependsOn": ["a"]},
          |  "c": {"dependsOn": ["b"]}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A { val x = 1 }
          |/b/src/main/scala/b/B.scala
          |package b
          |object B { val x = a.A.x }
          |/c/src/main/scala/c/C.scala
          |package c
          |object C { val x: String = b.B.x }
          |""".stripMargin
      )
      didOpen = server.didOpen("c/src/main/scala/c/C.scala")
      _ <- server.executeCommand(ServerCommands.CancelCompile.id)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      isCancelled <- didOpen.map(_ => false).recover {
        case _: CancellationException => true
      }
      _ = Predef.assert(
        isCancelled,
        // NOTE(olafur): I don't know if this test is flaky, I suspect it might be but want to first give it a
        // try before we come up with a way to test cancellation more robustly.
        "expected didOpen future to fail with Cancellation Exception. " +
          "If this happens frequently for unrelated changes, then this may be a flaky test that needs refactoring. " +
          "If this assertion is flaky, feel free to remove it until it's refactored."
      )
      _ <- server.executeCommand(ServerCommands.CascadeCompile.id)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|c/src/main/scala/c/C.scala:2:28: error: type mismatch;
           | found   : Int
           | required: String
           |object C { val x: String = b.B.x }
           |                           ^^^^^
           |""".stripMargin
      )
    } yield ()
  }
}
