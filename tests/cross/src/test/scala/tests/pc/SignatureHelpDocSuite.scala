package tests.pc

import tests.BaseSignatureHelpSuite

object SignatureHelpDocSuite extends BaseSignatureHelpSuite {
  checkDoc(
    "curry",
    """
      |object a {
      |  Option(1).fold("")(_ => @@)
      |}
    """.stripMargin,
    """|Returns the result of applying $f to this $option's
       | value if the $option is nonempty.
       |fold[B](ifEmpty: => B)(f: Int => B): B
       |                       ^^^^^^^^^^^
       |  @param ifEmpty `.
       |  @param f Int => ???  to this $option's
       |""".stripMargin
  )

  checkDoc(
    "curry2",
    """
      |object a {
      |  Option(1).fold("@@")
      |}
    """.stripMargin,
    """|Returns the result of applying $f to this $option's
       | value if the $option is nonempty.
       |fold[B](ifEmpty: => B)(f: Int => B): B
       |        ^^^^^^^^^^^^^
       |  @param ifEmpty String `.
       |  @param f to this $option's
       |""".stripMargin
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
       |  @param f Int => ???
       |""".stripMargin
  )
  checkDoc(
    "too-many",
    """
      |object a {
      |  Option(1, 2, @@2)
      |}
    """.stripMargin,
    // FIXME: https://github.com/scalameta/metals/issues/518
    // The expected output is broken here.
    """|An Option factory which creates Some(x) if the argument is not null,
       | and None if it is null.
       |apply[A](x: A): Option[A]
       |         ^^^^
       |  @param A n Option factory which creates Some(x) if the argument is not null,
       |  @param x (Int, Int, Int) ) if the argument is not null,
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
       |""".stripMargin
  )
  checkDoc(
    "default",
    """
      |object A {
      |  new scala.util.control.Exception.Catch(@@)
      |}
    """.stripMargin,
    """|A container class for catch/finally logic.
       |<init>(pf: Exception.Catcher[T], fin: Option[Exception.Finally] = None, rethrow: Throwable => Boolean = shouldRethrow): Exception.Catch[T]
       |       ^^^^^^^^^^^^^^^^^^^^^^^^
       |  @param pf Partial function used when applying catch logic to determine result value
       |  @param fin ally logic.
       |  @param rethrow if you want to probably
       |""".stripMargin
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
    """|valueOf(d: Double): String
       |        ^^^^^^^^^
       |valueOf(f: Float): String
       |valueOf(l: Long): String
       |valueOf(i: Int): String
       |valueOf(c: Char): String
       |valueOf(b: Boolean): String
       |valueOf(data: Array[Char]): String
       |valueOf(obj: Any): String
       |""".stripMargin
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
       |  @param value s of type
       |""".stripMargin
  )
}
