package tests.pc

import tests.BaseSignatureHelpSuite

class SignatureHelpPatternSuite extends BaseSignatureHelpSuite {

  check(
    "case",
    """
      |object Main {
      |  List(1 -> 2).map {
      |    case (a, @@) =>
      |  }
      |}
      |""".stripMargin,
    """|map[B](f: ((Int, Int)) => B): List[B]
       |       ^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.12" -> """|map[B, That](f: ((Int, Int)) => B)(implicit bf: CanBuildFrom[List[(Int, Int)],B,That]): That
                   |             ^^^^^^^^^^^^^^^^^^^^
                   |""".stripMargin,
      "3" -> """|map[B](f: A => B): List[B]
                |       ^^^^^^^^^
                |""".stripMargin
    )
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
           |""".stripMargin,
      "3" ->
        """|unapply[A](x$0: Some[A]): Option[A]
           |           ^^^^^^^^^^^^
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|unapply[T](x$1: generic2.Two[T]): generic2.Two[T]
           |           ^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|unapply[C[_$1], T](x$1: generic3.HKT[C, T]): generic3.HKT[C, T]
           |                   ^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
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
