package tests

import scala.meta.internal.metals.ServerCommands

import ch.epfl.scala.bsp4j.StatusCode

class CancelCompileLspSuite extends BaseLspSuite("compile-cancel") {

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": {"dependsOn": ["a"]},
          |  "c": {"dependsOn": ["b"]}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |import scala.reflect.macros.blackbox.Context
          |import scala.language.experimental.macros
          |
          |object A {
          |  val x = 123
          |  def sleep(): Unit = macro sleepImpl
          |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
          |    import c.universe._
          |    // Sleep for 3 seconds
          |    Thread.sleep(3000)
          |    reify { () }
          |  }
          |}
          |
          |/b/src/main/scala/b/B.scala
          |package b
          |object B { 
          |  a.A.sleep()
          |  val x = a.A.x 
          |}
          |/c/src/main/scala/c/C.scala
          |package c
          |object C { val x: String = b.B.x }
          |""".stripMargin
      )
      _ <- server.server.buildServerPromise.future
      (compileReport, _) <- server.server.compilations
        .compileFile(
          workspace.resolve("c/src/main/scala/c/C.scala")
        )
        .zip {
          // wait until the compilation start
          Thread.sleep(1000)
          server.executeCommand(ServerCommands.CancelCompile)
        }
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertEquals(compileReport.getStatusCode(), StatusCode.CANCELLED)
      _ <- server.server.compilations.compileFile(
        workspace.resolve("c/src/main/scala/c/C.scala")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|c/src/main/scala/c/C.scala:2:28: error: type mismatch;
           | found   : Int
           | required: String
           |object C { val x: String = b.B.x }
           |                           ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}
