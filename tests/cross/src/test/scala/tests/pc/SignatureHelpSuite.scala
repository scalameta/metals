package tests.pc

import tests.BaseSignatureHelpSuite

object SignatureHelpSuite extends BaseSignatureHelpSuite {
  override def beforeAll(): Unit = ()
  check(
    "method",
    """
      |object a {
      |  assert(true, ms@@)
      |}
    """.stripMargin,
    """|assert(assertion: Boolean, message: => Any): Unit
       |                           ^^^^^^^^^^^^^^^
       |assert(assertion: Boolean): Unit
       |""".stripMargin
  )
  check(
    "empty",
    """
      |object a {
      |  assert(@@)
      |}
    """.stripMargin,
    """|assert(assertion: Boolean, message: => Any): Unit
       |assert(assertion: Boolean): Unit
       |       ^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "erroneous",
    """
      |object a {
      |  Option(1).fold("")(_ => a@@)
      |}
    """.stripMargin,
    """|fold[B](ifEmpty: => B)(f: Int => B): B
       |                       ^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "canbuildfrom2",
    """
      |object a {
      |  List(1).map(@@)
      |}
    """.stripMargin,
    """|map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |             ^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "ctor",
    """
      |object a {
      |  new scala.util.Random(@@)
      |}
    """.stripMargin,
    """|<init>(): Random
       |<init>(seed: Int): Random
       |<init>(seed: Long): Random
       |<init>(self: java.util.Random): Random
       |""".stripMargin
  )
  check(
    "ctor1",
    """
      |object a {
      |  new ProcessBuilder(@@)
      |}
    """.stripMargin,
    """|<init>(x$1: String*): ProcessBuilder
       |<init>(x$1: List[String]): ProcessBuilder
       |""".stripMargin
  )
  check(
    "ctor2",
    """
      |object a {
      |  new Some(10@@)
      |}
    """.stripMargin,
    """|<init>(value: Int): Some[Int]
       |       ^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|<init>(x: Int): Some[Int]
           |       ^^^^^^
           |""".stripMargin
    )
  )

  check(
    "apply",
    """
      |object a {
      |  def apply(a: Int): Int = a
      |  def apply(b: String): String = b
      |  a(""@@)
      |}
    """.stripMargin,
    """|apply(b: String): String
       |      ^^^^^^^^^
       |apply(a: Int): Int
       |""".stripMargin
  )
  check(
    "partial",
    """
      |object a {
      |  Option(1).collect {
      |   case@@
      |  }
      |}
    """.stripMargin,
    """|collect[B](pf: PartialFunction[Int,B]): Option[B]
       |           ^^^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "nested",
    """
      |object a {
      |  List(Option(1@@))
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |         ^^^^
       |""".stripMargin
  )
  check(
    "nested2",
    """
      |object a {
      |  List(Opt@@ion(1))
      |}
    """.stripMargin,
    """|apply[A](xs: A*): List[A]
       |         ^^^^^^
       |""".stripMargin
  )
  check(
    "nested3",
    """
      |object a {
      |  List(Option(@@))
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |""".stripMargin
  )
  check(
    "vararg",
    """
      |object a {
      |  List(1, 2@@
      |}
    """.stripMargin,
    """|apply[A](xs: A*): List[A]
       |         ^^^^^^
       |""".stripMargin
  )
  check(
    "tparam",
    """
      |object a {
      |  identity[I@@]
      |}
    """.stripMargin,
    """|identity[A](x: A): A
       |         ^
       |""".stripMargin
  )
  check(
    "tparam2",
    """
      |object a {
      |  Option.empty[I@@]
      |}
    """.stripMargin,
    """|empty[A]: Option[A]
       |      ^
       |""".stripMargin
  )
  check(
    "tparam3",
    """
      |object a {
      |  Option[I@@]
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |      ^
       |""".stripMargin
  )
  check(
    "tparam4",
    """
      |object a {
      |  Map.empty[I@@]
      |}
    """.stripMargin,
    """|empty[K, V]: Map[K,V]
       |      ^
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|empty[A, B]: Map[A,B]
           |      ^
           |""".stripMargin
    )
  )
  check(
    "tparam5",
    """
      |object a {
      |  List[String](1).lengthCompare(@@)
      |}
    """.stripMargin,
    """|lengthCompare(len: Int): Int
       |              ^^^^^^^^
       |""".stripMargin
  )
  check(
    "error1",
    """
      |object a {
      |  Map[Int](1 @@-> "").map {
      |  }
      |}
    """.stripMargin,
    ""
  )
  check(
    "for",
    """
      |object a {
      |  for {
      |    i <- Option(1)
      |    j < 1.to(i)
      |    if i > j
      |    k@@ = i + j
      |    l <- j.to(k)
      |  } yield l
      |}
    """.stripMargin,
    ""
  )

  check(
    "for1",
    """
      |object a {
      |  for {
      |    i <- List(1)
      |    k = {
      |      Option(10@@)
      |    }
      |  } yield k
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |         ^^^^
       |""".stripMargin
  )

  check(
    "for2",
    """
      |object a {
      |  for {
      |    i <- List(1)
      |    if i < 0
      |    k = 100
      |    j <- i.to(@@)
      |  } yield k
      |}
    """.stripMargin,
    """|to(end: T): NumericRange.Inclusive[T]
       |   ^^^^^^
       |to(end: T, step: T): NumericRange.Inclusive[T]
       |to(end: Int): Range.Inclusive
       |to(end: Int, step: Int): Range.Inclusive
       |to(end: T): FractionalProxy#ResultWithoutStep
       |to(end: T, step: T): NumericRange.Inclusive[T]
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|to(end: T): FractionalProxy#ResultWithoutStep
           |   ^^^^^^
           |to(end: T, step: T): NumericRange.Inclusive[T]
           |to(end: T): NumericRange.Inclusive[T]
           |to(end: T, step: T): NumericRange.Inclusive[T]
           |to(end: Int): Range.Inclusive
           |to(end: Int, step: Int): Range.Inclusive
           |""".stripMargin
    )
  )

  check(
    "bounds",
    """
      |object a {
      |  Map.empty[Int, String].applyOrElse(1@@)
      |}
    """.stripMargin,
    """|applyOrElse[K1 <: Int, V1 >: String](x: K1, default: K1 => V1): V1
       |                                     ^^^^^
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|applyOrElse[A1 <: Int, B1 >: String](x: A1, default: A1 => B1): B1
           |                                     ^^^^^
           |""".stripMargin
    )
  )

  check(
    "error",
    """
      |object a {
      |  Map[Int](1 @@-> "").map {
      |  }
      |}
    """.stripMargin,
    ""
  )

  check(
    "named",
    """
      |case class User(name: String = "John", age: Int = 42)
      |object A {
      |  User(age = 1, @@)
      |}
    """.stripMargin,
    """|apply(<age: Int = {}>, <name: String = {}>): User
       |                       ^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "named1",
    """
      |case class User(name: String = "John", age: Int = 42)
      |object A {
      |  User(name = "", @@)
      |}
    """.stripMargin,
    """|apply(name: String = {}, age: Int = {}): User
       |                         ^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "named2",
    """
      |object A {
      |  def user(name: String, age: Int) = age
      |  user(na@@me = "", age = 42)
      |}
    """.stripMargin,
    """|user(name: String, age: Int): Int
       |     ^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "named3",
    """
      |object A {
      |  def user(name: String, age: Int): Int = age
      |  def user(name: String, age: Int, street: Int): Int = age
      |  def x = user(str@@eet = 42, name = "", age = 2)
      |}
    """.stripMargin,
    """|user(name: String, age: Int, street: Int): Int
       |     ^^^^^^^^^^^^
       |user(name: String, age: Int): Int
       |""".stripMargin
  )
  check(
    "short-name",
    """
      |object A {
      |  new scala.util.control.Exception.Catch(@@)
      |}
    """.stripMargin,
    """|<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = {}, rethrow: Throwable => Boolean = {}): Exception.Catch[T]
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "short-name1",
    """
      |object A {
      |  new java.util.HashMap[String, Int]().computeIfAbsent(@@)
      |}
    """.stripMargin,
    """|computeIfAbsent(x$1: String, x$2: Function[_ >: String, _ <: Int]): Int
       |                ^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "curry5",
    """
      |object a {
      |  def curry(a: Int)(c: Int) = a
      |  curry(1)(3@@)
      |}
    """.stripMargin,
    """|
       |curry(a: Int)(c: Int): Int
       |              ^^^^^^
       |""".stripMargin
  )
  check(
    "last-arg",
    """
      |object A {
      |  Option(a @@)
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |         ^^^^
       |""".stripMargin
  )
}
