package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.SourceOrganizeImports

import tests.codeactions.BaseCodeActionLspSuite

class Scala211ActionsLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala211

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
