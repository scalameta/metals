package tests.pc

import tests.BaseSignatureHelpSuite

class SignatureHelpSuite extends BaseSignatureHelpSuite {

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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|assert(assertion: Boolean): Unit
           |assert(assertion: Boolean, message: => Any): Unit
           |                           ^^^^^^^^^^^^^^^
           |""".stripMargin
    )
  )
  check(
    "empty",
    """
      |object a {
      |  assert(@@)
      |}
    """.stripMargin,
    """|assert(assertion: Boolean): Unit
       |       ^^^^^^^^^^^^^^^^^^
       |assert(assertion: Boolean, message: => Any): Unit
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|assert(assertion: Boolean): Unit
           |       ^^^^^^^^^^^^^^^^^^
           |assert(assertion: Boolean, message: => Any): Unit
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|map[B](f: Int => B): List[B]
           |       ^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "ctor",
    """
      |object a {
      |  val random = new scala.util.Random(@@)
      |}
    """.stripMargin,
    """|<init>(): Random
       |<init>(seed: Int): Random
       |<init>(seed: Long): Random
       |<init>(self: util.Random): Random
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|Random()
           |Random(seed: Int)
           |Random(seed: Long)
           |Random(self: java.util.Random)
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" -> """|ProcessBuilder(x$0: String*)
                |               ^^^^^^^^^^^^
                |ProcessBuilder(x$0: java.util.List[String])
                |""".stripMargin
    )
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
      "3" ->
        """|Some[A](value: A)
           |        ^^^^^^^^
           |""".stripMargin,
      "2.11" -> """|<init>(x: Int): Some[Int]
                   |       ^^^^^^
                   |""".stripMargin
    )
  )

  check(
    "ctor3",
    """
      |object a {
      |  import java.io.File
      |  new File(@@)
      |}
    """.stripMargin,
    """|<init>(x$1: URI): File
       |<init>(x$1: File, x$2: String): File
       |<init>(x$1: String, x$2: String): File
       |<init>(x$1: String): File
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|File(x$0: java.net.URI)
           |     ^^^^^^^^^^^^^^^^^
           |File(x$0: java.io.File, x$1: String)
           |File(x$0: String, x$1: String)
           |File(x$0: String)
           |""".stripMargin
    )
  )
  check(
    "ctor4",
    """
      |object a {
      |  new java.io.File(@@)
      |}
               """.stripMargin,
    """|<init>(x$1: URI): File
       |<init>(x$1: File, x$2: String): File
       |<init>(x$1: String, x$2: String): File
       |<init>(x$1: String): File
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|File(x$0: java.net.URI)
           |     ^^^^^^^^^^^^^^^^^
           |File(x$0: java.io.File, x$1: String)
           |File(x$0: String, x$1: String)
           |File(x$0: String)
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|collect[B](pf: PartialFunction[Int, B]): Option[B]
           |           ^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|apply[A](elems: A*): List[A]
           |         ^^^^^^^^^
           |""".stripMargin
    )
  )
  check(
    "nested3",
    """
      |object a {
      |  List(Option(@@))
      |}
    """.stripMargin,
    """|apply[A](x: A): Option[A]
       |         ^^^^
       |""".stripMargin
  )
  check(
    "vararg",
    """
      |object a {
      |  List(1, 2@@)
      |}
    """.stripMargin,
    """|apply[A](xs: A*): List[A]
       |         ^^^^^^
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" -> """|apply[A](elems: A*): List[A]
                       |         ^^^^^^^^^
                       |""".stripMargin
    )
  )

  check(
    "tparam".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
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
    "tparam2".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
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
    "tparam3".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
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
    "tparam4".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
    """
      |object a {
      |  Map.empty[I@@]
      |}
    """.stripMargin,
    """|empty[K, V]: Map[K,V]
       |      ^
       |""".stripMargin,
    compat = Map(
      "3" -> """|empty[K, V]: Map[K, V]
                |      ^
                |""".stripMargin,
      "2.11" -> """|empty[A, B]: Map[A,B]
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
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|lengthCompare(len: Int): Int
           |              ^^^^^^^^
           |lengthCompare(that: Iterable[_]): Int
           |""".stripMargin,
      "3" ->
        """|lengthCompare(len: Int): Int
           |              ^^^^^^^^
           |lengthCompare(that: Iterable[?]): Int
           |""".stripMargin
    )
  )

  check(
    "error1".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |object a {
      |  Map[Int](1 @@-> "").map {
      |  }
      |}
    """.stripMargin,
    "",
    compat = Map(
      "3" ->
        """|apply[K, V](elems: (K, V)*): Map[K, V]
           |            ^^^^^^^^^^^^^^
           |""".stripMargin
    )
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
       |to(end: T): Range.Partial[T,NumericRange[T]]
       |""".stripMargin,
    stableOrder = false,
    compat = Map(
      "2.13" ->
        """|to(end: T): NumericRange.Inclusive[T]
           |   ^^^^^^
           |to(end: Int): Range.Inclusive
           |to(end: Int, step: Int): Range.Inclusive
           |to(end: T, step: T): NumericRange.Inclusive[T]
           |""".stripMargin,
      "3" ->
        """|to(end: Int): scala.collection.immutable.Range.Inclusive
           |   ^^^^^^^^
           |to(end: Int, step: Int): scala.collection.immutable.Range.Inclusive
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
      "2.11" -> """|applyOrElse[A1 <: Int, B1 >: String](x: A1, default: A1 => B1): B1
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
    "",
    compat = Map(
      "3.0" -> "",
      "3.1" -> "",
      "3" ->
        """|apply[K, V](elems: (K, V)*): Map[K, V]
           |            ^^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "case-class",
    """
      |import java.{util => ju}
      |import javax.annotation.Nullable
      |
      |
      |case class TreeViewNode(
      |    viewId: String,
      |    @Nullable nodeUri: String,
      |    label: String,
      |    @Nullable command: String = null,
      |    @Nullable icon: String = null,
      |    @Nullable tooltip: String = null,
      |    // One of "collapsed", "expanded" or "none"
      |    @Nullable collapseState: String = null,
      |)
      |
      |object O{
      |  val viewId = ""
      |  val rootUri = ""
      |  val title = ""
      |  def root: TreeViewNode =
      |    TreeViewNode(
      |      vi@@ewId,
      |      rootUri,
      |      title,
      |      collapseState = "",
      |    )
      |}
      |
      |
    """.stripMargin,
    """|apply(viewId: String, nodeUri: String, label: String, command: String = null, icon: String = null, tooltip: String = null, collapseState: String = null): TreeViewNode
       |      ^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|apply(viewId: String, nodeUri: String, label: String, command: String, icon: String, tooltip: String, collapseState: String): case-class.TreeViewNode
           |      ^^^^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "case-class2",
    """
      |import java.{util => ju}
      |import javax.annotation.Nullable
      |
      |
      |case class TreeViewNode(
      |    viewId: String,
      |    @Nullable nodeUri: String,
      |    label: String,
      |    @Nullable command: String = null,
      |    // One of "collapsed", "expanded" or "none"
      |    @Nullable collapseState: String = null,
      |)
      |
      |object O{
      |  def root: TreeViewNode =
      |    val viewId = ""
      |    val rootUri = ""
      |    val title = ""
      |    TreeViewNode(
      |      vi@@ewId,
      |      rootUri,
      |      title,
      |      collapseState = "",
      |    )
      |}
      |
      |
    """.stripMargin,
    """|apply(<viewId: String>, <nodeUri: String>, <label: String>, <collapseState: String = ...>, <command: String = ...>): TreeViewNode
       |      ^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|apply(viewId: String, nodeUri: String, label: String, command: String, collapseState: String): case-class2.TreeViewNode
           |      ^^^^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "named",
    """
      |case class User(name: String = "John", age: Int = 42)
      |object A {
      |  User(age = 1, @@)
      |}
    """.stripMargin,
    """|apply(<age: Int = 42>, <name: String = "John">): User
       |                       ^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|apply(name: String, age: Int): named.User
           |                    ^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "named1",
    """
      |case class User(name: String = "John", age: Int = 42)
      |object A {
      |  User(name = "", @@)
      |}
    """.stripMargin,
    """|apply(name: String = "John", age: Int = 42): User
       |                             ^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|apply(name: String, age: Int): named1.User
           |                    ^^^^^^^^
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|user(name: String, age: Int, street: Int): Int
           |                             ^^^^^^^^^^^
           |user(name: String, age: Int): Int
           |""".stripMargin
    )
  )

  check(
    "named4",
    """
      |object A {
      |  identity(x = @@)
      |}
    """.stripMargin,
    """|identity[A](x: A): A
       |            ^^^^
       |""".stripMargin
  )

  check(
    "short-name",
    """
      |object A {
      |  new scala.util.control.Exception.Catch(@@)
      |}
    """.stripMargin,
    """|<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = ..., rethrow: Throwable => Boolean = ...): Exception.Catch[T]
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      // TODO short names are not supported yet
      "3" ->
        """|Catch[T](pf: scala.util.control.Exception.Catcher[T], fin: Option[scala.util.control.Exception.Finally], rethrow: Throwable => Boolean)
           |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|computeIfAbsent(x$1: String, x$2: Function[_ >: String <: Object, _ <: Int]): Int
           |                ^^^^^^^^^^^
           |""".stripMargin,
      // TODO short names are not supported yet
      "3" ->
        """|computeIfAbsent(x$0: String, x$1: java.util.function.Function[? >: String, ? <: Int]): Int
           |                ^^^^^^^^^^^
           |""".stripMargin
    )
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

  check(
    "last-arg1",
    """
      |object A {
      |  List[Int]("").map(a => @@)
      |}
    """.stripMargin,
    """|map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |             ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|map[B](f: Int => B): List[B]
           |       ^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "last-arg2",
    """
      |object A {
      |  List(1).map(a => 2 @@)
      |}
    """.stripMargin,
    """|map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |             ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|map[B](f: Int => B): List[B]
           |       ^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "last-arg3",
    """
      |  trait TypeClass[F[_]]
      |  object App {
      |    final class TypeClassOps[F[_], A](private val a: F[A]) extends AnyVal {
      |      def map[G[_]](fn: A => G[A])(implicit T: TypeClass[F]): G[A] = ???
      |    }
      |    implicit def conv[F[A], A](e: F[A])(implicit T: TypeClass[F]): TypeClassOps[F, A] = new TypeClassOps(e)
      |    class App[F[_]:TypeClass] {
      |      null.asInstanceOf[F[Int]].map(a => @@)
      |    }
      |  }
    """.stripMargin,
    """|map[G[_]](fn: Int => G[Int])(implicit T: TypeClass[F]): G[Int]
       |          ^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|map[G[_$3]](fn: Int => G[Int])(using T: last-arg3.TypeClass[F]): G[Int]
           |            ^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "evidence".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
    """
      |object a {
      |  Array.empty[@@]
      |}
    """.stripMargin,
    """|empty[T: ClassTag]: Array[T]
       |      ^^^^^^^^^^^
       | """.stripMargin
  )

  check(
    "implicit-conv",
    """
      |case class Text[T](value: T)
      |object Text {
      |  implicit def conv[T](e: T): Text[T] = Text(e)
      |}
      |object a {
      |  def foo[T](e: Text[T]): T = e.value
      |  foo(4@@2)
      |}
    """.stripMargin,
    """|foo[T](e: Text[T]): T
       |       ^^^^^^^^^^
       | """.stripMargin,
    compat = Map(
      "3" ->
        """|conv[T](e: T): implicit-conv.Text[T]
           |        ^^^^
           |""".stripMargin
    )
  )

  check(
    "type".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
    """
      |object a {
      |  val x: Map[Int, Stri@@ng]
      |}
    """.stripMargin,
    """|Map[A, B]: Map
       |       ^
       | """.stripMargin,
    compat = Map(
      "3" ->
        """|Map[K, V]: Map
           |       ^
           | """.stripMargin
    )
  )

  check(
    "type1".tag(
      IgnoreScalaVersion.for3LessThan("3.2.1-RC1-bin-20220628-65a86ae-NIGHTLY")
    ),
    """
      |object a {
      |  val x: Map[Int, Stri@@]
      |}
    """.stripMargin,
    """|Map[A, B]: Map
       |       ^
       | """.stripMargin,
    compat = Map(
      "3" ->
        """|Map[K, V]: Map
           |       ^
           | """.stripMargin
    )
  )

  check(
    "off-by-one".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1")
    ),
    """
      |object a {
      |  identity(42)@@
      |}
      |""".stripMargin,
    "",
    compat = Map(
      "3" ->
        """|identity[A](x: A): A
           |            ^^^^
           |""".stripMargin
    )
  )

  check(
    "off-by-one2",
    """
      |object a {
      |  identity(42@@)
      |}
      |""".stripMargin,
    """|identity[A](x: A): A
       |            ^^^^
       |""".stripMargin
  )

  check(
    "between-parens".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |object a {
      |  Option(1).fold(2)@@(_ + 1)
      |}
      |""".stripMargin,
    "",
    compat = Map(
      "3.0" -> "",
      "3.1" -> "",
      "3" ->
        """|fold[B](ifEmpty: => B)(f: Int => B): B
           |        ^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "between-parens2",
    """
      |object a {
      |  Option(1).fold(2@@)(_ + 1)
      |}
      |""".stripMargin,
    """|fold[B](ifEmpty: => B)(f: Int => B): B
       |        ^^^^^^^^^^^^^
       |""".stripMargin
  )

  check(
    "between-parens3".tag(
      IgnoreScalaVersion.for3LessThan("3.2.0-RC1-bin-20220519-ee9cc8f-NIGHTLY")
    ),
    """
      |object a {
      |  Option(1).fold(2)(@@_ + 1)
      |}
      |""".stripMargin,
    """|fold[B](ifEmpty: => B)(f: Int => B): B
       |                       ^^^^^^^^^^^
       |""".stripMargin
  )

  check(
    "default-args2",
    """
      |object Main {
      |  def deployment(
      |    fst: String,
      |    snd: Int = 1,
      |  ): Option[Int] = ???
      |  val abc = deployment(@@)
      |}
      |""".stripMargin,
    """|deployment(fst: String, snd: Int = 1): Option[Int]
       |           ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|deployment(fst: String, snd: Int): Option[Int]
           |           ^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "default-args3",
    """
      |object Main {
      |  def deployment(str: String)(
      |    fst: String,
      |    snd: Int = 1,
      |  ): Option[Int] = ???
      |  val abc = deployment("str")(
      |    @@
      |  )
      |}
      |""".stripMargin,
    """|deployment(fst: String, snd: Int = ...): Option[Int]
       |           ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|deployment(str: String)(fst: String, snd: Int): Option[Int]
           |                        ^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "default-args4",
    """
      |object Main {
      |  def deployment(str: String)(opt: Option[Int])(
      |    fst: String,
      |    snd: Int = 1,
      |  ): Option[Int] = ???
      |  val abc = deployment("str")(None)(
      |    @@
      |  )
      |}
      |""".stripMargin,
    """|deployment(fst: String, snd: Int = ...): Option[Int]
       |           ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|deployment(str: String)(opt: Option[Int])(fst: String, snd: Int): Option[Int]
           |                                          ^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "default-args5",
    """
      |object Main {
      |  def deployment(str: String)(opt: Option[Int] = None)(
      |    fst: String,
      |    snd: Int = 1,
      |  ): Option[Int] = ???
      |  val abc = deployment("str")(
      |    @@
      |  )
      |}
      |""".stripMargin,
    """|deployment(str: String)(opt: Option[Int] = None)(fst: String, snd: Int = 1): Option[Int]
       |                        ^^^^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|deployment(str: String)(opt: Option[Int])(fst: String, snd: Int): Option[Int]
           |                        ^^^^^^^^^^^^^^^^
           |""".stripMargin,
      "2.11" -> ""
    )
  )

  check(
    "default-args6".tag(IgnoreScala2),
    """
      |object Main {
      |  def deployment(using str: String)(
      |    fst: String,
      |    snd: Int = 1,
      |  ): Option[Int] = ???
      |  val abc = deployment(using "str")(
      |    @@
      |  )
      |}
      |""".stripMargin,
    """|deployment(using str: String)(fst: String, snd: Int): Option[Int]
       |                              ^^^^^^^^^^^
       |""".stripMargin
  )

  check(
    "generic-refinement-1",
    """
      |trait Foo[T] {
      |  def bar(t1: T, t2: T): Int = ???
      |  def bar(i: Int): Int = ???
      |}
      |object Baz extends Foo[String]
      |object Main {
      |  Baz.bar(@@)
      |}
      |""".stripMargin,
    """|bar(i: Int): Int
       |    ^^^^^^
       |bar(t1: String, t2: String): Int
       |""".stripMargin
  )

  check(
    "generic-refinement-2",
    """
      |trait Foo[T] {
      |  def bar(t1: T, t2: T): Int = ???
      |  def bar(i: Int): Int = ???
      |}
      |object Baz extends Foo[String]
      |object Main {
      |  Baz.bar("foo", @@)
      |}
      |""".stripMargin,
    """|bar(t1: String, t2: String): Int
       |                ^^^^^^^^^^
       |bar(i: Int): Int
       |""".stripMargin
  )

  check(
    "generic-refinement-3",
    """
      |trait Foo[T] {
      |  def apply(t1: T, t2: T): Int = ???
      |  def apply(i: Int): Int = ???
      |}
      |object Baz extends Foo[String]
      |object Main {
      |  Baz(@@)
      |}
      |""".stripMargin,
    """|apply(i: Int): Int
       |      ^^^^^^
       |apply(t1: String, t2: String): Int
       |""".stripMargin
  )

  check(
    "generic-refinement-4",
    """
      |abstract class Base1[A, B, C, R] extends Base2[(A, B, C), R] {
      |  def apply(a: A, b: B, c: C): R = ???
      |}
      |abstract class Base2[IN, OUT] extends Base3 {
      |  type In = IN
      |  type Out = OUT
      |}
      |trait Base3 {
      |  type In
      |  type Out
      |
      |  def apply(in: In): Out = ???
      |}
      |
      |object Baz extends Base1[String, Int, Double, Boolean]
      |object Main {
      |  Baz("foo", @@)
      |}
      |""".stripMargin,
    """|apply(a: String, b: Int, c: Double): Boolean
       |apply(in: (String, Int, Double)): Boolean
       |""".stripMargin
  )

  check(
    "generic-refinement-5",
    """
      |abstract class Base1[A, B, C, R] extends Base2[(A, B, C), R] {
      |  def apply(a: A, b: B, c: C): R = ???
      |}
      |abstract class Base2[IN, OUT] extends Base3 {
      |  type In = IN
      |  type Out = OUT
      |}
      |trait Base3 {
      |  type In
      |  type Out
      |
      |  def apply(in: In): Out = ???
      |}
      |
      |object Baz extends Base1[String, Int, Double, Boolean]
      |object Main {
      |  Baz(@@)
      |}
      |""".stripMargin,
    """|apply(in: (String, Int, Double)): Boolean
       |      ^^^^^^^^^^^^^^^^^^^^^^^^^
       |apply(a: String, b: Int, c: Double): Boolean
       |""".stripMargin
  )

  check(
    "implicit-conversions",
    """
      |class Foo
      |class Bar
      |object Baz {
      |  implicit def foo2bar(foo: Foo): Bar = ???
      |
      |  def takesBar(bar: Bar) = ()
      |  takesBar(new Fo@@o)
      |}
      |""".stripMargin,
    """|takesBar(bar: Bar): Unit
       |         ^^^^^^^^
       |""".stripMargin
  )

  check(
    "class-annotation-constructor",
    """
      |class AnAnnotation(i: Int, j: Int) extends annotation.StaticAnnotation
      |
      |@AnAnnotation(123, 4@@56)
      |class Bar
      |""".stripMargin,
    """<init>(i: Int, j: Int): AnAnnotation
      |               ^^^^^^
      |""".stripMargin
  )

  check(
    "field-annotation-constructor",
    """
      |class AnAnnotation(i: Int, j: Int) extends annotation.StaticAnnotation
      |
      |class Bar {
      |  @AnAnnotation(123@@, 456)
      |  def bar = "foo"
      |}
      |""".stripMargin,
    """<init>(i: Int, j: Int): AnAnnotation
      |               ^^^^^^
      |""".stripMargin
  )

  check(
    "annotation-subtree",
    """
      |object Helper {
      |  def func(i: Int, b: Boolean): Int = ???
      |}
      |
      |class AnAnnotation(i: Int, j: Int) extends annotation.StaticAnnotation
      |
      |@AnAnnotation(Helper.func(1, @@true), 3)
      |class Bar
      |""".stripMargin,
    """func(i: Int, b: Boolean): Int
      |             ^^^^^^^^^^
      |""".stripMargin
  )

  check(
    "superclass",
    """
      |class Foo(val someField: Int)
      |object Bar extends Foo(@@)
      |""".stripMargin,
    """|<init>(someField: Int): Foo
       |       ^^^^^^^^^^^^^^
       |""".stripMargin
  )

  check(
    "superclass-2",
    """
      |class Foo(val someField: Int, val someOtherField: Int)
      |object Bar extends Foo(1, @@)
      |""".stripMargin,
    """|<init>(someField: Int, someOtherField: Int): Foo
       |                       ^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )

  check(
    "superclass-3",
    """
      |class Foo(val someField: Int, val someOtherField: Int)(someOtherField2: Int)
      |object Bar extends Foo(1, 3)(@@)
      |""".stripMargin,
    """|<init>(someField: Int, someOtherField: Int)(someOtherField2: Int): Foo
       |                                            ^^^^^^^^^^^^^^^^^^^^
       |""".stripMargin
  )
}
