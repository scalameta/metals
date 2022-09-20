package tests.pc

import tests.BaseCompletionSuite

class CompletionScalaCliSuite extends BaseCompletionSuite {
  check(
    "simple",
    """|//> using lib "io.cir@@
       |package A
       |""".stripMargin,
    "io.circe",
  )

}
