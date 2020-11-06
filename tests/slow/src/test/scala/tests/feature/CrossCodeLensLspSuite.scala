package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCodeLensLspSuite

class CrossCodeLensLspSuite extends BaseCodeLensLspSuite("cross-code-lens") {

  check("main-method-scala3", scalaVersion = Some(V.scala3))(
    """|package foo
       |
       |<<run>><<debug>>
       |@main def mainMethod(): Unit = {
       |  println("Hello world!")
       |}
       |""".stripMargin
  )

}
