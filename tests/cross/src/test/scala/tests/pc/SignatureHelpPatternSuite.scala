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

  check(
    "generic1",
    """
      |object Main {
      |  Option(1) match {
      |    case Some(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|unapply(value: A): Some[A]
       |        ^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|unapply(x: A): Some[A]
           |        ^^^^
           |""".stripMargin
    )
  )

  check(
    "generic2",
    """
      |case class Two[T](a: T, b: T)
      |object Main {
      |  (null: Any) match {
      |    case Two(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|unapply(a: T, b: T): Two[T]
       |        ^^^^
       |""".stripMargin
  )

  check(
    "generic3",
    """
      |case class HKT[C[_], T](a: C[T])
      |object Main {
      |  (null: Any) match {
      |    case HKT(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|unapply(a: C[T]): HKT[C,T]
       |        ^^^^^^^
       |""".stripMargin
  )

  check(
    "negative",
    """
      |class HKT[C[_], T](a: C[T])
      |object HKT {
      |  def unapply(a: Int): Option[(Int, Int)] = Some(2 -> 2)
      |}
      |object Main {
      |  (null: Any) match {
      |    case HKT(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|unapply(a: Int): Option[(Int, Int)]
       |        ^^^^^^
       |""".stripMargin
  )
}
