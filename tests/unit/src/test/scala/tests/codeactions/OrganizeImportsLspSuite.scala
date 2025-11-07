package tests.codeactions

import scala.meta.internal.metals.codeactions.OrganizeImportsQuickFix
import scala.meta.internal.metals.codeactions.SourceOrganizeImports

class OrganizeImportsLspSuite
    extends BaseCodeActionLspSuite("OrganizeImports") {
  val sourceKind: String = SourceOrganizeImports.kind
  val quickFixKind: String = OrganizeImportsQuickFix.kind
  val scalacOption: List[String] = List("-Wunused")
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
    kind = List(sourceKind),
    scalacOptions = scalacOption,
  )

  check(
    "basic-unsaved",
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
    """|package a
       |import scala.concurrent.Future
       |import scala.concurrent.duration._
       |// comment
       |
       |object A {
       |  val d = Duration(10, MICROSECONDS)
       |  val k = Future.successful(1)
       |}
       |""".stripMargin,
    kind = List(sourceKind),
    scalacOptions = scalacOption,
    changeFile = (txt: String) => {
      txt.replace(
        """|import scala.concurrent.ExecutionContext.global""".stripMargin,
        """|import scala.concurrent.ExecutionContext.global
           |import java.nio.file.Path
           |// comment""".stripMargin,
      )
    },
  )

  check(
    "basic-unsaved-2",
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
    """|package a
       |import scala.concurrent.Future
       |import scala.concurrent.duration._
       |// comment
       |
       |object A {
       |  val d = Duration(10, MICROSECONDS)
       |  val k = Future.successful(1)
       |}
       |""".stripMargin,
    kind = List(sourceKind),
    scalacOptions = scalacOption,
    changeFile = (txt: String) => {
      txt.replace(
        """|import scala.concurrent.ExecutionContext.global""".stripMargin,
        """|import scala.concurrent.ExecutionContext.global
           |import java.nio.files.Files
           |// comment""".stripMargin,
      )
    },
    expectError = true,
    expectNoDiagnostics = false,
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
    s"${SourceOrganizeImports.title}",
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
    kind = List(sourceKind),
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
    },
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
    s"${SourceOrganizeImports.title}",
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
    kind = List(sourceKind),
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption,
  )

  check(
    "basic-source-3",
    """
      |package a
      |import scala.concurrent.duration.*
      |import scala.concurrent.{Future<<>> as ScalaFuture}
      |import scala.concurrent.ExecutionContext.global
      |import scala.concurrent.ExecutionContext
      |
      |object A {
      |  implicit val ec: ExecutionContext = global
      |  val d = Duration(10, MICROSECONDS)
      |  val k = ScalaFuture.successful(1)
      |  ScalaFuture{ println("Hello!") }
      |}
      |""".stripMargin,
    s"${SourceOrganizeImports.title}",
    """|package a
       |import scala.concurrent.ExecutionContext
       |import scala.concurrent.ExecutionContext.global
       |import scala.concurrent.Future as ScalaFuture
       |import scala.concurrent.duration.*
       |
       |object A {
       |  implicit val ec: ExecutionContext = global
       |  val d = Duration(10, MICROSECONDS)
       |  val k = ScalaFuture.successful(1)
       |  ScalaFuture{ println("Hello!") }
       |}
       |""".stripMargin,
    kind = List(sourceKind),
    scalacOptions = scalacOption ++ List("-Xsource:3"),
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
    s"${OrganizeImportsQuickFix.title}",
    """|package a
       |
       |
       |
       |object A {
       |  val a = "no one wants unused imports"
       |}
       |""".stripMargin,
    kind = List(quickFixKind),
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption,
  )

  checkNoAction(
    "on-used",
    """|package a
       |
       |<<import scala.util.Try>>
       |
       |object A {
       |  val a = "everyone wants used imports"
       |  val b: Try[Unit] = ???
       |}
       |""".stripMargin,
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption,
  )

  check(
    "stale",
    """|package a
       |
       |<<import java.time.Instant>>
       |
       |object A {
       |  val a: Int = "no one wants unused imports"
       |}
       |""".stripMargin,
    s"${SourceOrganizeImports.title} (disabled)",
    """|package a
       |
       |import java.time.Instant
       |
       |object A {
       |  val a: Int = "no one wants unused imports"
       |}
       |""".stripMargin,
    kind = List(sourceKind),
    scalafixConf = scalafixConf(),
    scalacOptions = scalacOption,
    expectNoDiagnostics = false,
  )

  checkNoAction(
    "no-quickfix-available",
    """
      |<<>>
      |object A {
      |  val action = "quick fix should not be available"
      |}
      |""".stripMargin,
  )
}
