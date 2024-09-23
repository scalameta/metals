package tests.pc

import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.semver.SemVer.Version.{fromString => ver}

import tests.BaseSignatureHelpSuite

class SignatureHelpDocSuite extends BaseSignatureHelpSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

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
        """.stripMargin
  )

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
      "3" ->
        s"""|$foldLatestDocs
            |**Parameters**
            |- `ifEmpty`: the expression to evaluate if empty.
            |- `f`: the function to apply if nonempty.
            |fold[B](ifEmpty: => B)(f: Int => B): B
            |        ^^^^^^^^^^^^^
            |  @param ifEmpty the expression to evaluate if empty.
            |  @param f the function to apply if nonempty.
            |""".stripMargin
    )
  )

  val addedSpace: String = {
    val parsed = ver(BuildInfo.scalaCompilerVersion)
    if (parsed >= ver("2.13.11") && parsed < ver("3.0.0")) "" else " "
  }

  val docsAfter21313: String =
    s"""|Applies the given binary operator `op` to the given initial value `z` and all
        | elements of this collection, going left to right. Returns the initial value if this collection
        | is empty.
        |
        | "Going left to right" only makes sense if this collection is ordered: then if
        | `x`, `x`, ..., `x` are the elements of this collection, the result is
        | `op( op( ... op( op(z, x), x) ... ), x)`.
        |
        | If this collection is not ordered, then for each application of the operator, each
        | right operand is an element. In addition, the leftmost operand is the initial
        | value, and each other left operand is itself an application of the operator. The
        | elements of this collection and the initial value all appear exactly once in the
        | computation.
        |
        | Note: might return different results for different runs, unless the underlying collection type is ordered.
        | Note: will not terminate for infinite-sized collections.
        |
        |
        |**Type Parameters**
        |- `B`: The result type of the binary operator.
        |
        |**Parameters**
        |- `z`: An initial value.
        |- `op`: A binary operator.
        |
        |**Returns:** The result of applying `op` to `z` and all elements of this collection,
        |                   going left to right. Returns `z` if this collection is empty.
        |foldLeft[B](z: B)(op: (B, Int) => B): B
        |                  ^^^^^^^^^^^^^^^^^
        |  @param op (Int, Int) => Int
        |""".stripMargin
  checkDoc(
    "curry3",
    """
      |object a {
      |  List(1).foldLeft(0) {
      |   case @@
      |  }
      |}
    """.stripMargin,
    """|Applies a binary operator to a start value and all elements of this collection or iterator,
       | going left to right.
       |
       | Note: will not terminate for infinite-sized collections.
       | Note: might return different results for different runs, unless the
       |underlying collection type is ordered or the operator is associative
       |and commutative.
       |
       |
       |**Type Parameters**
       |- `B`: the result type of the binary operator.
       |
       |**Parameters**
       |- `z`: the start value.
       |- `op`: the binary operator.
       |
       |**Returns:** the result of inserting `op` between consecutive elements of this collection or iterator,
       |          going left to right with the start value `z` on the left:
       |
       |```
       |op(...op(z, x_1), x_2, ..., x_n)
       |```
       |          where `x, ..., x` are the elements of this collection or iterator.
       |          Returns `z` if this collection or iterator is empty.
       |foldLeft[B](z: B)(op: (B, Int) => B): B
       |                  ^^^^^^^^^^^^^^^^^
       |  @param op (Int, Int) => Int
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        s"""|Applies a binary operator to a start value and all elements of this collection,
            | going left to right.
            |
            | Note: will not terminate for infinite-sized collections.
            | Note: might return different results for different runs, unless the
            |underlying collection type is ordered or the operator is associative
            |and commutative.
            |
            |
            |**Type Parameters**
            |- `B`: the result type of the binary operator.
            |
            |**Parameters**
            |- `z`: the start value.
            |- `op`: the binary operator.
            |
            |**Returns:** the result of inserting `op` between consecutive elements of this collection,
            |          going left to right with the start value `z` on the left:
            |          `op(...op(z, x), x, ..., x)` where `x, ..., x`
            |          ${addedSpace}are the elements of this collection.
            |          Returns `z` if this collection is empty.
            |foldLeft[B](z: B)(op: (B, Int) => B): B
            |                  ^^^^^^^^^^^^^^^^^
            |  @param op (Int, Int) => Int
            |""".stripMargin,
      "2.13.13" -> docsAfter21313,
      "2.13.14" -> docsAfter21313,
      "2.13.15" -> docsAfter21313,
      "3" ->
        """|Applies the given binary operator `op` to the given initial value `z` and all
           | elements of this collection, going left to right. Returns the initial value if this collection
           | is empty.
           |
           | "Going left to right" only makes sense if this collection is ordered: then if
           | `x`, `x`, ..., `x` are the elements of this collection, the result is
           | `op( op( ... op( op(z, x), x) ... ), x)`.
           |
           | If this collection is not ordered, then for each application of the operator, each
           | right operand is an element. In addition, the leftmost operand is the initial
           | value, and each other left operand is itself an application of the operator. The
           | elements of this collection and the initial value all appear exactly once in the
           | computation.
           |
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           | Note: will not terminate for infinite-sized collections.
           |
           |
           |**Type Parameters**
           |- `B`: The result type of the binary operator.
           |
           |**Parameters**
           |- `z`: An initial value.
           |- `op`: A binary operator.
           |
           |**Returns:** The result of applying `op` to `z` and all elements of this collection,
           |                   going left to right. Returns `z` if this collection is empty.
           |foldLeft[B](z: B)(op: (B, Int) => B): B
           |                  ^^^^^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  checkDoc(
    "curry4".tag(IgnoreScalaVersion.for3LessThan("3.2.0-RC1")),
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
    """|Builds a new collection by applying a function to all elements of this general collection.
       |
       |
       |**Type Parameters**
       |- `B`: the element type of the returned collection.
       |- `That`: the class of the returned collection. Where possible, `That` is
       |the same class as the current collection class `Repr`, but this
       |depends on the element type `B` being admissible for that class,
       |which means that an implicit instance of type `CanBuildFrom[Repr, B, That]`
       |is found.
       |
       |**Parameters**
       |- `f`: the function to apply to each element.
       |- `bf`: an implicit value of class `CanBuildFrom` which determines
       |the result class `That` from the current representation type `Repr` and
       |the new element type `B`.
       |
       |**Returns:** a new general collection resulting from applying the given function
       |                 `f` to each element of this general collection and collecting the results.
       |map[B, That](f: Int => B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
       |             ^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|Builds a new collection by applying a function to all elements of this collection.
           |
           |
           |**Type Parameters**
           |- `B`: the element type of the returned collection.
           |
           |**Parameters**
           |- `f`: the function to apply to each element.
           |
           |**Returns:** a new collection resulting from applying the given function
           |               `f` to each element of this collection and collecting the results.
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|A tuple of 3 elements; the canonical representation of a [scala.Product3](scala.Product3).
           |
           |
           |**Constructor:** Create a new tuple with 3 elements. Note that it is more idiomatic to create a Tuple3 via `(t1, t2, t3)`
           |
           |**Parameters**
           |- `_1`: Element 1 of this Tuple3
           |- `_2`: Element 2 of this Tuple3
           |- `_3`: Element 3 of this Tuple3
           |apply[T1, T2, T3](_1: T1, _2: T2, _3: T3): (T1, T2, T3)
           |                                  ^^^^^^
           |""".stripMargin,
      ">=3.2.0-RC1-bin-20220610-30f83f7-NIGHTLY" ->
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
           |  @param x the value
           |""".stripMargin
    )
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
           |""".stripMargin,
      "3" ->
        """|Returns an immutable set containing only the specified object.
           |The returned set is serializable.
           |singleton[T](o: T): java.util.Set[T]
           |             ^^^^
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
       |- `pf`: Partial function used when applying catch logic to determine result value
       |- `fin`: Finally logic which if defined will be invoked after catch logic
       |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
       |<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = None, rethrow: Throwable => Boolean = shouldRethrow): Exception.Catch[T]
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |  @param pf Partial function used when applying catch logic to determine result value
       |  @param fin Finally logic which if defined will be invoked after catch logic
       |  @param rethrow Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
       |""".stripMargin,
    compat = Map(
      "3" ->
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
           |- `pf`: Partial function used when applying catch logic to determine result value
           |- `fin`: Finally logic which if defined will be invoked after catch logic
           |- `rethrow`: Predicate on throwables determining when to rethrow a caught [Throwable](Throwable)
           |Catch[T](pf: scala.util.control.Exception.Catcher[T], fin: Option[scala.util.control.Exception.Finally], rethrow: Throwable => Boolean)
           |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
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
       |""".stripMargin,
    compat = Map(
      "3" -> """|File(uri: java.net.URI)
                |     ^^^^^^^^^^^^^^^^^
                |File(parent: java.io.File, child: String)
                |File(parent: String, child: String)
                |File(pathname: String)
                |""".stripMargin
    )
  )

  check(
    "java2",
    """
      |object a {
      |  "".substring(1@@)
      |}
    """.stripMargin,
    """|substring(beginIndex: Int): String
       |          ^^^^^^^^^^^^^^^
       |substring(beginIndex: Int, endIndex: Int): String
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|substring(beginIndex: Int, endIndex: Int): String
           |substring(beginIndex: Int): String
           |          ^^^^^^^^^^^^^^^
           |""".stripMargin
    )
  )

  check(
    "java3",
    """
      |object a {
      |  String.valueOf(1@@)
      |}
    """.stripMargin,
    """|valueOf(i: Int): String
       |        ^^^^^^
       |valueOf(d: Double): String
       |valueOf(f: Float): String
       |valueOf(l: Long): String
       |valueOf(c: Char): String
       |valueOf(b: Boolean): String
       |valueOf(data: Array[Char], offset: Int, count: Int): String
       |valueOf(data: Array[Char]): String
       |valueOf(obj: Any): String
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|valueOf(d: Double): String
           |valueOf(f: Float): String
           |valueOf(l: Long): String
           |valueOf(i: Int): String
           |        ^^^^^^
           |valueOf(c: Char): String
           |valueOf(b: Boolean): String
           |valueOf(data: Array[Char], offset: Int, count: Int): String
           |valueOf(data: Array[Char]): String
           |valueOf(obj: Object): String
           |""".stripMargin
    )
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
           |""".stripMargin,
      "3" ->
        """|valueOf(d: Double): String
           |        ^^^^^^^^^
           |valueOf(f: Float): String
           |valueOf(l: Long): String
           |valueOf(i: Int): String
           |valueOf(c: Char): String
           |valueOf(b: Boolean): String
           |valueOf(data: Array[Char], offset: Int, count: Int): String
           |valueOf(data: Array[Char]): String
           |valueOf(obj: Object): String
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
      "3" ->
        """|Class `Some[A]` represents existing values of type
           | `A`.
           |Some[A](value: A)
           |        ^^^^^^^^
           |""".stripMargin,
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
