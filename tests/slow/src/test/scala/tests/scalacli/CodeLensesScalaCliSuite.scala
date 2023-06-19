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

}
