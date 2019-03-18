package tests.pc

import tests.BaseCompletionSuite

object CompletionIssueSuite extends BaseCompletionSuite {
  check(
    "mutate",
    """package a
      |class Foo@@
      |""".stripMargin,
    ""
  )

  check(
    "issue-569",
    """package a
      |class Main {
      |  new Foo@@
      |}
    """.stripMargin,
    ""
  )
}
