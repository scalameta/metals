package tests.codeactions

import scala.meta.internal.mtags.CoursierComplete

import coursier.version.Version

class ActionableDiagnosticsSuite
    extends BaseCodeActionLspSuite("actionableDiagnostic") {

  val oldOsLibVersion: Version = Version("0.7.8")
  val newestOsLib: String = CoursierComplete
    .complete("com.lihaoyi::os-lib:")
    .headOption
    .getOrElse("0.8.1")

  check(
    "actionable-diagnostic-update",
    s"""|//> <<>>using lib "com.lihaoyi::os-lib:${oldOsLibVersion.repr}"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"Apply suggestion: com.lihaoyi::os-lib:0.7.8 is outdated, update to $newestOsLib",
    s"""|//> using lib "com.lihaoyi::os-lib:$newestOsLib"
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    scalaCliOptions = List("--actions", "-S", scalaVersion),
    expectNoDiagnostics = false,
    scalaCliLayout = true,
  )

}
