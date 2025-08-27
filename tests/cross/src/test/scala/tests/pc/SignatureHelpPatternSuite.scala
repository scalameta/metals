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
    """|map[B, That](f: ((Int, Int)) => B)(implicit bf: CanBuildFrom[List[(Int, Int)],B,That]): That
       |             ^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" -> """|map[B](f: ((Int, Int)) => B): List[B]
                       |       ^^^^^^^^^^^^^^^^^^^^
                       |""".stripMargin
    )
  )

  check(
    "generic1".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |object Main {
      |  Option(1) match {
      |    case Some(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|(value: Int)
       | ^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.11" -> """|(x: Int)
                   | ^^^^^^
                   |""".stripMargin
    )
  )

  check(
    "generic2".tag(IgnoreScalaVersion.for3LessThan("3.2.0-RC1")),
    """
      |case class Two[T](a: T, b: T)
      |object Main {
      |  (null: Any) match {
      |    case Two(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|(a: T, b: T)
       | ^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(a: T, b: T)
           | ^^^^
           |""".stripMargin
    )
  )

  check(
    "generic3".tag(IgnoreScalaVersion.for3LessThan("3.2.0-RC1")),
    """
      |case class HKT[C[_], T](a: C[T])
      |object Main {
      |  (null: Any) match {
      |    case HKT(@@) =>
      |  }
      |}
      |""".stripMargin,
    """|(a: C[T])
       | ^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(a: C[T])
           | ^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "generic4".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |  case class Two[A, B](a: A, b: B)
      |  object Main {
      |    new Two(1, "") match {
      |      case Two(@@) =>
      |    }
      |  }
      |""".stripMargin,
    """|(a: Int, b: String)
       | ^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(a: Int, b: String)
           | ^^^^^^
           |""".stripMargin
    )
  )

  check(
    "generic5".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "non-synthetic-unapply".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "non-synthetic-unapply-second".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "pat".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |case class Person(name: String, age: Int)
      |object a {
      |  null.asInstanceOf[Person] match {
      |    case Person(@@)
      |}
    """.stripMargin,
    """|(name: String, age: Int)
       | ^^^^^^^^^^^^
       | """.stripMargin,
    compat = Map(
      "3" ->
        """|(name: String, age: Int)
           | ^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "pat1".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "pat2".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
       |""".stripMargin,
    compat = Map(
      "3.0" -> """|(String)
                  | ^^^^^^
                  |""".stripMargin,
      "3.1" -> """|(String)
                  | ^^^^^^
                  |""".stripMargin,
      "3" -> """|(List[String])
                | ^^^^^^^^^^^^
                |""".stripMargin
    )
  )

  check(
    "pat3".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "pat4".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "",
    compat = Map(
      "3" ->
        """|(String, String)
           |         ^^^^^^
           |""".stripMargin
    )
  )

  check(
    "pat5".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
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
    "pat6".tag(IgnoreScalaVersion.for3LessThan("3.2.0-RC1")),
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(Option[Int])
           | ^^^^^^^^^^^
           |""".stripMargin
    )
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

  check(
    "identical-param-types".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1")
    ),
    """
      |case class Baz(arg1: Int, arg2: Int, arg3: Int)
      |object Test {
      |  (null: Baz) match {
      |    case Baz(123, 4@@56, 789) => ()
      |  }
      |}
    """.stripMargin,
    """|(arg1: Int, arg2: Int, arg3: Int)
       |            ^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(arg1: Int, arg2: Int, arg3: Int)
           |            ^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "inheritance".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1")
    ),
    """
      |abstract class Base(val x: Int)
      |case class Child(override val x: Int, y: String) extends Base(x)
      |object Test {
      |  (null: Child) match {
      |    case Child(@@) =>
      |  }
      |}
    """.stripMargin,
    """|(x: Int, y: String)
       | ^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|(x: Int, y: String)
           | ^^^^^^
           |""".stripMargin
    )
  )

}
