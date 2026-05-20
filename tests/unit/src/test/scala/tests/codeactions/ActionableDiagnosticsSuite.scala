package tests.codeactions

import scala.meta.internal.metals.BuildInfo

class ActionableDiagnosticsSuite
    extends BaseCodeActionLspSuite(
      "actionableDiagnostics"
    ) {

  check(
    "insert-companion-object",
    """|
       |<<private private val x = 1>>
       |""".stripMargin,
    s"""|Remove repeated modifier: "private"
        |Explain with -explain
        |""".stripMargin,
    """|
       |private  val x = 1
       |""".stripMargin,
    scalaVersion = BuildInfo.latestScala3Next,
  )
}
