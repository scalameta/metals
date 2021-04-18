package tests.codeactions

import scala.meta.internal.metals.codeactions.OrganizeImports

class OrganizeImportsLspSuite
    extends BaseCodeActionLspSuite("OrganizeImports") {
  val kind: String = OrganizeImports.kind
  val scalacOption: List[String] = List("-Ywarn-unused-import")
  def scalafixConf(path: String = "/.scalafix.conf"): String =
    s"""|$path
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
        |  "re:javax?\\\\."
        |  "*"
        |]
        |
        |""".stripMargin

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
      |""".stripMargin,
    kind = List(kind),
    scalacOptions = scalacOption
  )

  check(
    "basic-with-custom-config",
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
      |""".stripMargin,
    kind = List(kind),
    scalafixConf = scalafixConf("/project/scalafix.conf"),
    scalacOptions = scalacOption,
    configuration = Some {
      val configPath = workspace
        .resolve(
          "project/scalafix.conf"
        )
        .toString()
        .replace("\\", "\\\\")

      s"""|{
          |  "scalafixConfigPath": "$configPath"
          |}""".stripMargin
    }
  )

  check(
    "basic-with-existing-config",
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
      |""".stripMargin,
    kind = List(kind),
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption
  )

  check(
    "on-unused",
    """|package a
       |
       |<<import java.time.Instant>>
       |
       |object A {
       |  val a = "no one wants unused imports"
       |}
       |""".stripMargin,
    s"${OrganizeImports.title}",
    """|package a
       |
       |
       |
       |object A {
       |  val a = "no one wants unused imports"
       |}
       |""".stripMargin,
    kind = List(kind),
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption
  )
}
