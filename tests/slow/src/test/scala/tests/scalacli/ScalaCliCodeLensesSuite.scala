package tests.scalacli

import tests.BaseCodeLensLspSuite

class ScalaCliCodeLensesSuite
    extends BaseCodeLensLspSuite("scala-cli-code-lenses") {

  private val scalaCliScriptPath = "a/src/main/scala/a/main.sc"
  test("run-script") {
    cleanWorkspace()
    for {

      _ <- initialize(
        s"""/.bsp/scala-cli.json
           |${BaseScalaCliSuite.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPath
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPath)
      _ <- assertCodeLenses(
        scalaCliScriptPath,
        """|<<run>><<debug>>
           |print("oranges are nice")
           |""".stripMargin,
      )
    } yield ()
  }

  private val scalaCliScriptPathTop = "main.sc"
  test("run-script-top") {
    cleanWorkspace()
    for {

      _ <- initialize(
        s"""/.bsp/scala-cli.json
           |${BaseScalaCliSuite.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPathTop
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPathTop)
      _ <- assertCodeLenses(
        scalaCliScriptPathTop,
        """|<<run>><<debug>>
           |print("oranges are nice")
           |""".stripMargin,
      )
    } yield ()
  }
}
