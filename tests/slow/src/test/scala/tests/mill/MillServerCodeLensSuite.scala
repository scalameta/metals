package tests.mill

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.debug.MUnit
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCodeLensLspSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

class MillServerCodeLensSuite
    extends BaseCodeLensLspSuite("mill-server-lenses", MillServerInitializer) {

  /*
   * There is some flakiness involved, possibly https://github.com/com-lihaoyi/mill/issues/2826
   * We added some timeouts to make sure the test is correctly retried when fetching lenses.
   */
  test("run-mill-lens", maxRetry = 3) {
    cleanWorkspace()
    writeLayout(
      MillBuildLayout(
        """|/a/src/Main.scala
           |package foo
           |
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |     println("Hello java!")
           |  }
           |}
           |/a/test/src/Foo.scala
           |// no test lense as debug is not supported
           |class Foo extends munit.FunSuite {}
           |""".stripMargin,
        V.scala3,
        testDep = Some(MUnit),
      )
    )

    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      _ <- server.didOpen("a/src/Main.scala")
      _ <- server.didSave("a/src/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ <- assertCodeLenses(
        "a/src/Main.scala",
        """|package foo
           |
           |<<run>><<debug>>
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |     println("Hello java!")
           |  }
           |}""".stripMargin,
      )
      _ <- assertCodeLenses(
        "a/test/src/Foo.scala",
        """|// no test lense as debug is not supported
           |<<test>><<debug test>>
           |class Foo extends munit.FunSuite {}
           |""".stripMargin,
      )
      lenses <- server.codeLenses("a/src/Main.scala")
      _ = assert(lenses.size > 0, "No lenses were generated!")
      command = lenses.head.getCommand()
      _ = assertEquals(runFromCommand(command, None), Some("Hello java!"))
    } yield ()
  }
}
