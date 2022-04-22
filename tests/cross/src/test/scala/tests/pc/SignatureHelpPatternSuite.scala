package tests.pc

import tests.BaseSignatureHelpSuite

class SignatureHelpPatternSuite extends BaseSignatureHelpSuite {

  // @tgodzik docs not yet supported for Scala 3
  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

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
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|map[B](f: ((Int, Int)) => B): List[B]
           |       ^^^^^^^^^^^^^^^^^^^^
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
    """|(Int)
       | ^^^
       |""".stripMargin
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
    """|(Any, Any)
       | ^^^
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
    """|(Any)
       | ^^^
       |""".stripMargin
  )

  check(
    "generic4",
    """
      |  case class Two[A, B](a: A, b: B)
      |  object Main {
      |    new Two(1, "") match {
      |      case Two(@@) =>
      |    }
      |  }
      |""".stripMargin,
    """|(Int, String)
       | ^^^
       |""".stripMargin
  )

  check(
    "generic5",
    """
      |class Two[A, B](a: A, b: B)
      |object Two {
      |  def unapply[A, B](t: Two[A, B]): Option[(A, B)] = None
      |}
      |
      |object Main {
      |  val tp = new Two(1, "") 
      |  tp match {
      |    case Two(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|(Int, String)
       | ^^^
       |""".stripMargin
  )

  check(
    "non-synthetic-unapply",
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
    """|(Int, Int)
       | ^^^
       |""".stripMargin
  )

  check(
    "non-synthetic-unapply-second",
    """
      |class HKT[C[_], T](a: C[T])
      |object HKT {
      |  def unapply(a: Int): Option[(Int, Int)] = Some(2 -> 2)
      |}
      |object Main {
      |  (null: Any) match {
      |    case HKT(1, @@) =>
      |  }
      |}
      |""".stripMargin,
    """|(Int, Int)
       |      ^^^
       |""".stripMargin
  )

  check(
    "pat",
    """
      |case class Person(name: String, age: Int)
      |object a {
      |  null.asInstanceOf[Person] match {
      |    case Person(@@)
      |}
    """.stripMargin,
    """|(String, Int)
       | ^^^^^^
       | """.stripMargin
  )

  check(
    "pat1",
    """
      |class Person(name: String, age: Int)
      |object Person {
      |  def unapply(p: Person): Option[(String, Int)] = ???
      |}
      |object a {
      |  null.asInstanceOf[Person] match {
      |    case Person(@@) =>
      |  }
      |}
    """.stripMargin,
    """|(String, Int)
       | ^^^^^^
       | """.stripMargin
  )

  check(
    "pat2",
    """
      |object a {
      |  val Number = "$a, $b".r
      |  "" match {
      |    case Number(@@)
      |  }
      |}
    """.stripMargin,
    """|(List[A])
       | ^^^^^^^
       |""".stripMargin
  )

  check(
    "pat3",
    """
      |object And {
      |  def unapply[A](a: A): Some[(A, A)] = Some((a, a))
      |}
      |object a {
      |  "" match {
      |    case And("", s@@)
      |  }
      |}
      |""".stripMargin,
    """|(String, String)
       |         ^^^^^^
       |""".stripMargin
  )

  check(
    "pat4",
    """
      |object & {
      |  def unapply[A](a: A): Some[(A, A)] = Some((a, a))
      |}
      |object a {
      |  "" match {
      |    case "" & s@@
      |  }
      |}
    """.stripMargin,
    // NOTE(olafur) it's kind of accidental that this doesn't return "unapply[A](..)",
    // the reason is that the qualifier of infix unapplies doesn't have a range position
    // and signature help excludes qualifiers without range positions in order to exclude
    // generated code. Feel free to update this test to have the same expected output as
    // `pat3` without regressing signature help in othere cases like partial functions that
    // generate qualifiers with offset positions.
    ""
  )

  check(
    "pat5",
    """
      |object OpenBrowserCommand {
      |  def unapply(command: String): Option[Int] = {
      |    Some(1)
      |  }
      |
      |  "" match {
      |    case OpenBrowserCommand(@@) =>
      |  }
      |}
    """.stripMargin,
    """|(Int)
       | ^^^
       |""".stripMargin
  )

  check(
    "pat6",
    """
      |object OpenBrowserCommand {
      |  def unapply(command: String): Option[Option[Int]] = {
      |    Some(Some(1))
      |  }
      |
      |  "" match {
      |    case OpenBrowserCommand(@@) =>
      |  }
      |}
    """.stripMargin,
    """|(Option[A])
       | ^^^^^^^^^
       |""".stripMargin
  )

  check(
    "pat-negative",
    """
      |object And {
      |  def unapply[A](a: A): Some[(A, A)] = Some((a, a))
      |}
      |object a {
      |  And.unapply(@@)
      |}
    """.stripMargin,
    """|unapply[A](a: A): Some[(A, A)]
       |           ^^^^
       | """.stripMargin
  )

}
