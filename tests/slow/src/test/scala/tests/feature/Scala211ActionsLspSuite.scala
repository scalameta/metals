package tests.feature

import scala.meta.internal.metals.codeactions.SourceOrganizeImports

import tests.codeactions.BaseCodeActionLspSuite

class Scala211ActionsLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  /**
   * Should work with Metals 1.6.7, we no longer publish Scala 2.11.12 artifacts.
   */
  override protected val scalaVersion: String = "2.11.12"

  check(
    "basic",
    """
      |package a
      |import scala.concurrent.duration._
      |import scala.concurrent.Future<<>>
      |import scala.concurrent.ExecutionContext.global
      |
      |object A {
      |  val d = Duration(10, MICROSECONDS)
      |  val k = Future.successful(1)
      |}
      |""".stripMargin,
    s"${SourceOrganizeImports.title}",
    """
      |package a
      |import scala.concurrent.Future
      |import scala.concurrent.duration._
      |
      |object A {
      |  val d = Duration(10, MICROSECONDS)
      |  val k = Future.successful(1)
      |}
      |""".stripMargin,
    kind = List(SourceOrganizeImports.kind),
    scalacOptions = List("-Ywarn-unused-import"),
  )
}
