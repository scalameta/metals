package tests.codeactions

class MillifyScalaCliDependencyCodeActionSuite
    extends BaseCodeActionLspSuite("millifyScalaCliDependency") {
  val sbtStyleDependency =
    """"org.scalameta" %% "munit" % "0.7.26""""

  val convertedDependency = """"org.scalameta::munit:0.7.26""""
  val convertTo = s"""//> using lib $convertedDependency"""

  check(
    "convert-dependency",
    s"""|//> <<>>using lib $sbtStyleDependency
        |
        |object Hello extends App {
        |  println("Hello")
        |}
        |""".stripMargin,
    s"""Convert to $convertTo""",
    s"""|//> using lib $convertedDependency
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
