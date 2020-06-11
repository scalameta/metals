package tests.codeactions

import scala.meta.internal.metals.codeactions.OrganizeImports

class OrganizeImportsLspSuite
    extends BaseCodeActionLspSuite("OrganizeImports") {
  val kind: String = OrganizeImports.kind
  val scalacOption: List[String] = List("-Ywarn-unused-import")
  val scalafixConf: String =
    """|/.scalafix.conf
       |rules = [
       |  OrganizeImports,
       |  ExplicitResultTypes,
       |  RemoveUnused
       |]
       |
       |ExplicitResultTypes.rewriteStructuralTypesToNamedSubclass = false
       |
       |RemoveUnused.imports = false
       |
       |OrganizeImports.groupedImports = Explode
       |OrganizeImports.expandRelative = true
       |OrganizeImports.removeUnused = true
       |OrganizeImports.groups = [
       |  "scala."
       |  "re:javax?\\."
       |  "*"
       |]
       |
       |""".stripMargin

  check(
    "basic organize imports",
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
    s"${OrganizeImports.title}",
    """
      |package a
      |import scala.concurrent.Future
      |import scala.concurrent.duration._
      |
      |object A {
      |  val d = Duration(10, MICROSECONDS)
      |  val k = Future.successful(1)
      |}
      |""".stripMargin.replace("'", "\""),
    kind = List(kind),
    scalacOptions = scalacOption
  )

  check(
    "basic organize imports with existing config",
    """
      |package a
      |import scala.concurrent.duration._
      |import java.util.Optional<<>>
      |
      |object A {
      |  val d = Duration(10, MICROSECONDS)
      |  val optional = Optional.empty()
      |}
      |""".stripMargin,
    s"${OrganizeImports.title}",
    """
      |package a
      |import scala.concurrent.duration._
      |
      |import java.util.Optional
      |
      |object A {
      |  val d = Duration(10, MICROSECONDS)
      |  val optional = Optional.empty()
      |}
      |""".stripMargin.replace("'", "\""),
    kind = List(kind),
    scalafixConf = scalafixConf,
    scalacOptions = scalacOption
  )

}
