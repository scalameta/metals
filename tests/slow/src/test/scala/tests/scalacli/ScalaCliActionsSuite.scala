package tests.scalacli

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.mtags.BuildInfo.scalaCompilerVersion
import scala.meta.internal.mtags.CoursierComplete

import coursier.version.Version

class ScalaCliActionsSuite
    extends BaseScalaCLIActionSuite("actionableDiagnostic") {

  val oldOsLibVersion: Version = Version("0.7.8")
  val coursierComplete = new CoursierComplete(scalaCompilerVersion)
  val newestOsLib: String = coursierComplete
    .complete("com.lihaoyi::os-lib:")
    .headOption
    .getOrElse("0.8.1")

  checkScalaCLI(
    "actionable-diagnostic-update",
    s"""|//> using lib "<<>>com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""Apply suggestion: "os-lib is outdated, update to $newestOsLib"""",
    s"""|//> using lib "com.lihaoyi::os-lib:$newestOsLib"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
  )

  checkNoActionScalaCLI(
    "actionable-diagnostic-out-of-range",
    s"""|//> <<>>using lib "com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
  )

  checkScalaCLI(
    "auto-import",
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |
        |object A {
        |  <<Future>>.successful(2)
        |}
        |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> using lib "org.typelevel::cats-core:2.9.0"
        |import scala.concurrent.Future
        |
        |object A {
        |  Future.successful(2)
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    fileName = "A.sc",
  )

}
