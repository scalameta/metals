package tests.mill

import scala.concurrent.duration.Duration

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCodeLensLspSuite
import tests.MillBuildLayout
import tests.MillServerInitializer

class MillServerCodeLensSuite
    extends BaseCodeLensLspSuite("mill-server-lenses", MillServerInitializer) {

  override def munitTimeout: Duration = Duration("4min")

  test("run-mill-lens", maxRetry = 3) {
    cleanWorkspace()
    writeLayout(
      MillBuildLayout(
        """|/MillMinimal/src/Main.scala
           |package foo
           |
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |     println("Hello java!")
           |  }
           |}
           |/MillMinimal/test/src/Foo.scala
           |// no test lense as debug is not supported
           |class Foo extends munit.FunSuite {}
           |""".stripMargin,
        V.scala213,
        V.millVersion,
        includeMunit = true,
      )
    )

    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.executeCommand(ServerCommands.GenerateBspConfig)
      _ <- server.didOpen("MillMinimal/src/Main.scala")
      _ <- server.didSave("MillMinimal/src/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ <- assertCodeLenses(
        "MillMinimal/src/Main.scala",
        """|package foo
           |
           |<<run>>
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |     println("Hello java!")
           |  }
           |}""".stripMargin,
      )
      _ <- assertCodeLenses(
        "MillMinimal/test/src/Foo.scala",
        """|// no test lense as debug is not supported
           |class Foo extends munit.FunSuite {}
           |""".stripMargin,
      )
      lenses <- server.codeLenses("MillMinimal/src/Main.scala")
      _ = assert(lenses.size > 0, "No lenses were generated!")
      command = lenses.head.getCommand()
      _ = assertEquals(runFromCommand(command), Some("Hello java!"))
    } yield ()
  }
}
