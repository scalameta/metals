package tests.scalacli

import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCodeLensLspSuite

class CodeLensesScalaCliSuite
    extends BaseCodeLensLspSuite("cross-code-lens-scalacli") {

  test("run-main-annotation-with-script") {
    cleanWorkspace()
    val path = "main.sc"
    for {
      _ <- initialize(
        ScalaCliBuildLayout(
          s"""|/$path
              |val x = 3
              |
              |def main() = {
              |  println("annotation")
              |}""".stripMargin,
          V.scala3,
        )
      )
      _ <- server.didOpen(path)
      _ <- assertCodeLenses(
        path,
        """|<<run (main_sc)>><<debug (main_sc)>>
           |val x = 3
           |
           |def main() = {
           |  println("annotation")
           |}""".stripMargin,
      )
    } yield ()
  }

  test("run-with-shebang") {
    cleanWorkspace()
    val path = "main.scala"
    for {
      _ <- initialize(
        ScalaCliBuildLayout(
          s"""|/$path
              |#!/usr/bin/env -S scala-cli shebang
              |object Main extends App {
              |    println(12)
              |}""".stripMargin,
          V.scala3,
        )
      )
      _ <- server.didOpen(path)
      _ <- assertCodeLenses(
        path,
        """|#!/usr/bin/env -S scala-cli shebang
           |<<run>><<debug>>
           |object Main extends App {
           |    println(12)
           |}""".stripMargin,
      )
    } yield ()
  }

  test("run-with-shebang-2") {
    cleanWorkspace()
    val path = "main.scala"
    for {
      _ <- initialize(
        ScalaCliBuildLayout(
          s"""|/$path
              |#!/usr/bin/env -S scala-cli shebang
              |//> using scala ${V.scala213}
              |object Main extends App {
              |    println(12)
              |}""".stripMargin,
          V.scala213,
        )
      )
      _ <- server.didOpen(path)
      _ <- assertCodeLenses(
        path,
        s"""|#!/usr/bin/env -S scala-cli shebang
            |//> using scala ${V.scala213}
            |<<run>><<debug>>
            |object Main extends App {
            |    println(12)
            |}""".stripMargin,
      )
    } yield ()
  }

  private val scalaCliScriptPath = "a/src/main/scala/a/main.sc"
  test("run-script") {
    cleanWorkspace()
    for {

      _ <- initialize(
        s"""/.bsp/scala-cli.json
           |${ScalaCli.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPath
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPath)
      _ <- assertCodeLenses(
        scalaCliScriptPath,
        """|<<run (main_sc)>><<debug (main_sc)>>
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
           |${ScalaCli.scalaCliBspJsonContent()}
           |/.scala-build/ide-inputs.json
           |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
           |/$scalaCliScriptPathTop
           |print("oranges are nice")""".stripMargin
      )
      _ <- server.didOpen(scalaCliScriptPathTop)
      _ <- assertCodeLenses(
        scalaCliScriptPathTop,
        """|<<run (main_sc)>><<debug (main_sc)>>
           |print("oranges are nice")
           |""".stripMargin,
      )
    } yield ()
  }
}
