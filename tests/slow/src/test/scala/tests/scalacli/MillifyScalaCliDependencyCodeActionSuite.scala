package tests.scalacli

import scala.meta.internal.metals.BuildInfo

class MillifyScalaCliDependencyCodeActionSuite
    extends BaseScalaCLIActionSuite("millifyScalaCliDependency") {
  val sbtStyleDependency =
    """"org.scalameta" %% "munit" % "0.7.26""""

  val convertedDependency = """"org.scalameta::munit:0.7.26""""
  val convertTo: String = s"""//> using lib $convertedDependency"""

  checkScalaCLI(
    "convert-dependency",
    s"""|//> <<>>using lib $sbtStyleDependency
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""Convert to $convertedDependency""",
    s"""|//> using lib $convertedDependency
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
  )

  val sbtStyleDependencyMultiSpace =
    """    "org.scalameta"     %% "munit"   % "0.7.26""""

  checkScalaCLI(
    "convert-dependency-multiple-whitespace",
    s"""|//> <<>>using lib $sbtStyleDependencyMultiSpace
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""Convert to $convertedDependency""",
    s"""|//> using lib $convertedDependency
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    expectNoDiagnostics = false,
  )

  checkScalaCLI(
    "convert-dependency-multiple",
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> <<>>using lib $sbtStyleDependencyMultiSpace
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""Convert to $convertedDependency""",
    s"""|//> using scala "${BuildInfo.scala213}"
        |//> using lib $convertedDependency
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    expectNoDiagnostics = false,
  )

}
