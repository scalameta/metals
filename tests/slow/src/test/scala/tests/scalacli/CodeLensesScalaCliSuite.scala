package tests.scalacli

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
        """|<<run>><<debug>>
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
}
