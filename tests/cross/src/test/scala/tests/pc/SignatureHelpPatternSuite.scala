package tests.pc

import tests.BaseSignatureHelpSuite

object SignatureHelpPatternSuite extends BaseSignatureHelpSuite {
  check(
    "case",
    """
      |object Main {
      |  List(1 -> 2).map {
      |    case (a, @@) =>
      |  }
      |}
      |""".stripMargin,
    """|map[B, That](f: ((Int, Int)) => B)(implicit bf: CanBuildFrom[List[(Int, Int)],B,That]): That
       |             ^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
}
