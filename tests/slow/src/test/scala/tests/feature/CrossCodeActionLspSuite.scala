package tests.feature

import scala.meta.internal.metals.BuildInfo

import tests.codeactions.BaseCodeActionLspSuite

class CrossCodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  checkNoAction(
    "val",
    """|package a
       |
       |object A {
       |  val al<<>>pha = 123
       |}
       |""".stripMargin
  )
}
