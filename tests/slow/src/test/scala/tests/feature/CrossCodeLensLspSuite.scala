package tests.feature

import tests.BaseCodeLensLspSuite
import scala.meta.internal.metals.{BuildInfo => V}

class CrossCodeLensLspSuite extends BaseCodeLensLspSuite("cross-code-lens") {

  check("main-method-dotty", scalaVersion = Some(V.scala3))(
    """|package foo
       |
       |<<run>><<debug>>
       |@main def mainMethod(): Unit = {
       |  println("Hello world!")
       |}
       |""".stripMargin
  )

}
