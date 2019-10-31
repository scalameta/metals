package tests.pc

import tests.BaseSignatureHelpSuite

object SignatureHelpDocSuite extends BaseSignatureHelpSuite {

  override def beforeAll(): Unit = {
    indexJDK()
    indexScalaLibrary()
  }

  val foldLatestDocs: String =
    """|Returns the result of applying `f` to this [scala.Option](scala.Option)'s
       | value if the [scala.Option](scala.Option) is nonempty.  Otherwise, evaluates
       | expression `ifEmpty`.
       |
       |This is equivalent to:
       |
       |```
       |option match {
       |  case Some(x) => f(x)
       |  case None    => ifEmpty
       |}
       |```
       |This is also equivalent to:
       |
       |```
       |option map f getOrElse ifEmpty
       |```""".stripMargin

  val foldOlderDocs1: String =
    """|Returns the result of applying `f` to this [scala.Option](scala.Option)'s
       | value if the [scala.Option](scala.Option) is nonempty.  Otherwise, evaluates
       | expression `ifEmpty`.
       |
       |
       |**Notes**
       |- This is equivalent to `[scala.Option](scala.Option) map f getOrElse ifEmpty`.
       |
       |**Parameters**
       |- `ifEmpty`: the expression to evaluate if empty.
       |- `f`: the function to apply if nonempty.
       |fold[B](ifEmpty: => B)(f: Int => B): B
       |                       ^^^^^^^^^^^
       |  @param ifEmpty the expression to evaluate if empty.
       |  @param f the function to apply if nonempty.
       |""".stripMargin

  checkDoc(
    "curry",
    """
      |object a {
      |  Option(1).fold("")(_ => @@)
      |}
    """.stripMargin,
    s"""$foldLatestDocs
       |**Parameters**
       |- `ifEmpty`: the expression to evaluate if empty.
       |- `f`: the function to apply if nonempty.
       |fold[B](ifEmpty: => B)(f: Int => B): B
       |                       ^^^^^^^^^^^
       |  @param ifEmpty the expression to evaluate if empty.
       |  @param f the function to apply if nonempty.
    """.stripMargin,
    compat = Map(
      "2.12.8" -> foldOlderDocs1,
      "2.12.7" -> foldOlderDocs1,
      "2.13" ->
        s"""$foldLatestDocs
           |**Parameters**
           |- `f`: the function to apply if nonempty.
           |- `ifEmpty`: the expression to evaluate if empty.
           |fold[B](ifEmpty: => B)(f: Int => B): B
           |                       ^^^^^^^^^^^
           |  @param ifEmpty the expression to evaluate if empty.
           |  @param f the function to apply if nonempty.
        """.stripMargin
    )
  )

  val foldOlderDocs2: String =
    """|Returns the result of applying `f` to this [scala.Option](scala.Option)'s
       | value if the [scala.Option](scala.Option) is nonempty.  Otherwise, evaluates
       | expression `ifEmpty`.
       |
       |
       |**Notes**
       |- This is equivalent to `[scala.Option](scala.Option) map f getOrElse ifEmpty`.
       |
       |**Parameters**
       |- `ifEmpty`: the expression to evaluate if empty.
       |- `f`: the function to apply if nonempty.
       |fold[B](ifEmpty: => B)(f: Int => B): B
       |        ^^^^^^^^^^^^^
       |  @param ifEmpty String the expression to evaluate if empty.
       |  @param f the function to apply if nonempty.
       |""".stripMargin

  checkDoc(
    "curry2",
    """
      |object a {
      |  Option(1).fold("@@")
      |}
    """.stripMargin,
    s"""|$foldLatestDocs
        |**Parameters**
        |- `ifEmpty`: the expression to evaluate if empty.
        |- `f`: the function to apply if nonempty.
        |fold[B](ifEmpty: => B)(f: Int => B): B
        |        ^^^^^^^^^^^^^
        |  @param ifEmpty String the expression to evaluate if empty.
        |  @param f the function to apply if nonempty.
        |""".stripMargin,
    compat = Map(
      "2.12.8" -> foldOlderDocs2,
      "2.12.7" -> foldOlderDocs2,
      "2.13" ->
        s"""|$foldLatestDocs
            |**Parameters**
            |- `f`: the function to apply if nonempty.
            |- `ifEmpty`: the expression to evaluate if empty.
            |fold[B](ifEmpty: => B)(f: Int => B): B
            |        ^^^^^^^^^^^^^
            |  @param ifEmpty String the expression to evaluate if empty.
            |  @param f the function to apply if nonempty.
            |""".stripMargin
    )
  )
  checkDoc(
    "curry3",
    """
      |object a {
      |  List(1).foldLeft(0) {
      |   case @@
      |  }
      |}
    """.stripMargin,
    """|
       |foldLeft[B](z: B)(op: (B, Int) => B): B
       |                  ^^^^^^^^^^^^^^^^^
       |  @param op (Int, Int) => Int
       |""".stripMargin
  )
  checkDoc(
    "curry4",
    """
      |object a {
      |  def curry(a: Int, b: Int)(c: Int) = a
      |  curry(1)(3@@)
      |}
    """.stripMargin,
    """|
       |curry(a: Int, b: Int)(c: Int): Int
       |                      ^^^^^^
       |""".stripMargin
  )
  checkDoc(
    "canbuildfrom",
    """
      |object a {
      |  List(1).map(x => @@)
      |}
    """.stripMargin,
    """|
       |map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |             ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|
           |map[B](f: Int => B): List[B]
           |       ^^^^^^^^^^^
           |""".stripMargin
    )
  )
  checkDoc(
    "too-many",
    """
      |object a {
      |  Option(1, 2, @@2)
      |}
    """.stripMargin,
    """|An Option factory which creates Some(x) if the argument is not null,
       | and None if it is null.
       |
       |
       |**Parameters**
       |- `x`: the value
       |
       |**Returns:** Some(value) if value != null, None if value == null
       |apply[A](x: A): Option[A]
       |         ^^^^
       |  @param x (Int, Int, Int) the value
       |""".stripMargin
  )
  checkDoc(
    "java5",
    """
      |object a {
      |  java.util.Collections.singleton(@@)
      |}
    """.stripMargin,
    """| Returns an immutable set containing only the specified object.
       |The returned set is serializable.
       |singleton[T](o: T): Set[T]
       |             ^^^^
       |  @param T <T> the class of the objects in the set
       |  @param o o the sole object to be stored in the returned set.
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """| Returns an immutable set containing only the specified object.
           |The returned set is serializable.
           |singleton[T <: Object](o: T): Set[T]
           |                       ^^^^
           |  @param T <T> the class of the objects in the set
           |  @param o o the sole object to be stored in the returned set.
           |""".stripMargin
    )
  )
  checkDoc(
    "default",
    """
      |object A {
      |  new scala.util.control.Exception.Catch(@@)
      |}
    """.stripMargin,
    """|A container class for catch/finally logic.
       |
       | Pass a different value for rethrow if you want to probably
       | unwisely allow catching control exceptions and other throwables
       | which the rest of the world may expect to get through.
       |
       |**Type Parameters**
       |- `T`: result type of bodies used in try and catch blocks
       |
       |**Parameters**
       |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
       |- `pf`: Partial function used when applying catch logic to determine result value
       |- `fin`: Finally logic which if defined will be invoked after catch logic
       |<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = None, rethrow: Throwable => Boolean = shouldRethrow): Exception.Catch[T]
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |  @param pf Partial function used when applying catch logic to determine result value
       |  @param fin Finally logic which if defined will be invoked after catch logic
       |  @param rethrow Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|A container class for catch/finally logic.
           |
           | Pass a different value for rethrow if you want to probably
           | unwisely allow catching control exceptions and other throwables
           | which the rest of the world may expect to get through.
           |
           |**Type Parameters**
           |- `T`: result type of bodies used in try and catch blocks
           |
           |**Parameters**
           |- `fin`: Finally logic which if defined will be invoked after catch logic
           |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
           |- `pf`: Partial function used when applying catch logic to determine result value
           |<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = None, rethrow: Throwable => Boolean = shouldRethrow): Exception.Catch[T]
           |       ^^^^^^^^^^^^^^^^^^^^^^^^
           |  @param pf Partial function used when applying catch logic to determine result value
           |  @param fin Finally logic which if defined will be invoked after catch logic
           |  @param rethrow Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
           |""".stripMargin
    )
  )
  check(
    "java",
    """
      |object a {
      |  new java.io.File(@@)
      |}
    """.stripMargin,
    """|<init>(uri: URI): File
       |<init>(parent: File, child: String): File
       |<init>(parent: String, child: String): File
       |<init>(pathname: String): File
       |""".stripMargin
  )
  check(
    "java2",
    """
      |object a {
      |  "".substring(1@@)
      |}
    """.stripMargin,
    """|substring(beginIndex: Int, endIndex: Int): String
       |substring(beginIndex: Int): String
       |          ^^^^^^^^^^^^^^^
       |""".stripMargin
  )
  check(
    "java3",
    """
      |object a {
      |  String.valueOf(1@@)
      |}
    """.stripMargin,
    """|valueOf(d: Double): String
       |valueOf(f: Float): String
       |valueOf(l: Long): String
       |valueOf(i: Int): String
       |        ^^^^^^
       |valueOf(c: Char): String
       |valueOf(b: Boolean): String
       |valueOf(data: Array[Char], offset: Int, count: Int): String
       |valueOf(data: Array[Char]): String
       |valueOf(obj: Any): String
       |""".stripMargin
  )
  check(
    "java4",
    """
      |object a {
      |  String.valueOf(@@)
      |}
    """.stripMargin,
    """|valueOf(obj: Any): String
       |        ^^^^^^^^
       |valueOf(data: Array[Char]): String
       |valueOf(b: Boolean): String
       |valueOf(c: Char): String
       |valueOf(d: Double): String
       |valueOf(f: Float): String
       |valueOf(i: Int): String
       |valueOf(l: Long): String
       |valueOf(data: Array[Char], offset: Int, count: Int): String
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|valueOf(data: Array[Char]): String
           |        ^^^^^^^^^^^^^^^^^
           |valueOf(b: Boolean): String
           |valueOf(c: Char): String
           |valueOf(d: Double): String
           |valueOf(f: Float): String
           |valueOf(i: Int): String
           |valueOf(l: Long): String
           |valueOf(obj: Object): String
           |valueOf(data: Array[Char], offset: Int, count: Int): String
           |""".stripMargin
    )
  )
  checkDoc(
    "ctor2",
    """
      |object a {
      |  new Some(10@@)
      |}
    """.stripMargin,
    """|Class `Some[A]` represents existing values of type
       | `A`.
       |<init>(value: Int): Some[Int]
       |       ^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|Class `Some[A]` represents existing values of type
           | `A`.
           |<init>(x: Int): Some[Int]
           |       ^^^^^^
           |""".stripMargin
    )
  )
  checkDoc(
    "markdown",
    """
      |object A {
      |  1.to(10).by(@@)
      |}
    """.stripMargin,
    // tests both @define and HTML expansion
    """|Create a new range with the `start` and `end` values of this range and
       | a new `step`.
       |
       |
       |**Returns:** a new range with a different step
       |by(step: Int): Range
       |   ^^^^^^^^^
       |""".stripMargin
  )
}
