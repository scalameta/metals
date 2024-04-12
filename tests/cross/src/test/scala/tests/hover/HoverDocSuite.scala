package tests.hover

import scala.meta.pc.HoverContentType

import tests.pc.BaseHoverSuite

class HoverDocSuite extends BaseHoverSuite {
  override def requiresJdkSources: Boolean = true

  override protected def requiresScalaLibrarySources: Boolean = true

  check(
    "doc",
    """object a {
      |  <<java.util.Collections.empty@@List[Int]>>
      |}
      |""".stripMargin,
    // Assert that the docstring is extracted.
    """|**Expression type**:
       |```scala
       |java.util.List[Int]
       |```
       |**Symbol signature**:
       |```scala
       |final def emptyList[T](): java.util.List[T]
       |```
       |Returns an empty list (immutable).  This list is serializable.
       |
       |This example illustrates the type-safe way to obtain an empty list:
       |
       |```
       |List<String> s = Collections.emptyList();
       |```
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|**Expression type**:
           |```scala
           |java.util.List[Int]
           |```
           |**Symbol signature**:
           |```scala
           |final def emptyList[T <: Object](): java.util.List[T]
           |```
           |Returns an empty list (immutable).  This list is serializable.
           |
           |This example illustrates the type-safe way to obtain an empty list:
           |
           |```
           |List<String> s = Collections.emptyList();
           |```
           |""".stripMargin,
      "3" ->
        """|**Expression type**:
           |```scala
           |java.util.List[Int]
           |```
           |**Symbol signature**:
           |```scala
           |final def emptyList[T](): java.util.List[T]
           |```
           |Returns an empty list (immutable).  This list is serializable.
           |
           |This example illustrates the type-safe way to obtain an empty list:
           |
           |```
           |List<String> s = Collections.emptyList();
           |```
           |""".stripMargin
    )
  )

  check(
    "doc-parent",
    """object a {
      |  <<List(12).hea@@dOption>>
      |}
      |""".stripMargin,
    // Assert that the docstring is extracted.
    """|```scala
       |def headOption: Option[Int]
       |```
       |Optionally selects the first element.
       | $orderDependent
       |
       |**Returns:** the first element of this traversable collection if it is nonempty,
       |          `None` if it is empty.
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|```scala
           |override def headOption: Option[Int]
           |```
           |Optionally selects the first element.
           | Note: might return different results for different runs, unless the underlying collection type is ordered.
           |
           |**Returns:** the first element of this iterable collection if it is nonempty,
           |          `None` if it is empty.
           |""".stripMargin
    )
  )

  check(
    "java-method",
    """|import java.nio.file.Paths
       |
       |object O{
       |  <<Paths.g@@et("")>>
       |}
       |""".stripMargin,
    """|```scala
       |def get(first: String, more: String*): Path
       |```
       |Converts a path string, or a sequence of strings that when joined form
       |a path string, to a `Path`.
       |""".stripMargin
  )

  check(
    "object",
    """|
       |/** 
       |  * Doc about object
       |  */
       |object Alpha {
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about object
       |
       |""".stripMargin
  )

  check(
    "object1",
    """|
       |/** 
       |  * Doc about object
       |  */
       |object Alpha {
       |  /**
       |    * Doc about method
       |    */
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about method
       |
       |""".stripMargin
  )

  check(
    "case-class".tag(IgnoreScala211),
    """|
       |/**
       |  * Doc about case class
       |  *
       |  */
       |case class Alpha(x: Int)
       |
       |/** 
       |  * Doc about object
       |  */
       |object Alpha {
       |  /**
       |    * Doc about method
       |    */
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about method
       |
       |""".stripMargin
  )

  check(
    "case-class1".tag(IgnoreScala211),
    """|
       |/**
       |  * Doc about case class
       |  *
       |  */
       |case class Alpha(x: Int)
       |
       |/** 
       |  * Doc about object
       |  */
       |object Alpha {
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about object
       |
       |""".stripMargin
  )

  check(
    "case-class2".tag(IgnoreScala211),
    """|
       |/**
       |  * Doc about case class
       |  *
       |  */
       |case class Alpha(x: Int)
       |
       |object Alpha {
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about case class
       |
       |""".stripMargin
  )

  check(
    "case-class3",
    """|
       |/**
       |  * Doc about case class
       |  *
       |  */
       |case class Alpha(x: Int)
       |
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Alpha
       |```
       |Doc about case class
       |
       |""".stripMargin
  )

  check(
    "class",
    """|
       |/**
       |  * Doc about class
       |  *
       |  */
       |class Alpha(x: Int)
       |
       |object Alpha {
       |  def apply(x: Int) = x + 1
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def apply(x: Int): Int
       |```
       |Doc about class
       |
       |""".stripMargin
  )

  check(
    "class-param",
    """|
       |/**
       |  * Doc about class
       |  *
       |  */
       |class Alpha(abc: Int) {
       |  val y = <<a@@bc>>
       |}
       |
       |""".stripMargin,
    """|```scala
       |private[this] val abc: Int
       |```
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|```scala
           |val abc: Int
           |```
           |""".stripMargin
    )
  )

  check(
    "case-class-param",
    """|
       |/**
       |  * Doc about class
       |  *
       |  */
       |case class Alpha(abc: Int) {
       |  val y = <<a@@bc>>
       |}
       |
       |""".stripMargin,
    """|```scala
       |val abc: Int
       |```
       |""".stripMargin
  )

  check(
    "enum-param".tag(IgnoreScala2),
    """|
       |/**
       |  * Doc about enum
       |  *
       |  */
       |enum Alpha(abc: Int):
       |  case Beta extends Alpha(1)
       |  val y = <<ab@@c>>
       |""".stripMargin,
    """|```scala
       |val abc: Int
       |```
       |""".stripMargin
  )

  check(
    "class-type-param",
    """|
       |/**
       |  * Doc about class
       |  *
       |  */
       |class Alpha[T](abc: T) {
       |  val y: <<@@T>> = abc
       |}
       |
       |""".stripMargin,
    """|```scala
       |T: T
       |```
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|```scala
           |type T: T
           |```
           |""".stripMargin
    )
  )

  check(
    "method-param",
    """|
       |object O {
       |  /**
       |    * Doc about method
       |    */
       |  def foo(abc: Int) = <<a@@bc>> + 1
       |
       |}
       |
       |""".stripMargin,
    """|```scala
       |abc: Int
       |```
       |""".stripMargin
  )

  check(
    "method-param2",
    """|
       |object O {
       |  /**
       |    * Doc about method
       |    */
       |  def foo(abc: Int)(foo: Int) = abc + <<f@@oo>>
       |
       |}
       |
       |""".stripMargin,
    """|```scala
       |foo: Int
       |```
       |""".stripMargin
  )

  check(
    "method-type-param",
    """|
       |object O {
       |  /**
       |    * Doc about method
       |    */
       |  def foo[T](abc: T): <<@@T>> = abc
       |
       |}
       |
       |""".stripMargin,
    """|```scala
       |T: T
       |```
       |""".stripMargin
  )

  check(
    "universal-apply".tag(IgnoreScala2),
    """|
       |/**
       |  * Doc about class
       |  *
       |  */
       |class Alpha(x: Int)
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|```scala
       |def this(x: Int): Alpha
       |```
       |Doc about class
       |
       |""".stripMargin
  )

  check(
    "doc-static-java",
    """
      |import java.nio.file.Paths
      |
      |class A {
      |  <<Pa@@ths>>.get("");
      |}
      |""".stripMargin,
    """|```scala
       |class java.nio.file.Paths
       |```
       |
       |This class consists exclusively of static methods that return a [Path](Path)
       |by converting a path string or [URI](URI).
       |""".stripMargin,
    compat = Map(
      "3" -> """|```scala
                |object Paths: java.nio.file
                |```
                |This class consists exclusively of static methods that return a [Path](Path)
                |by converting a path string or [URI](URI).
                |""".stripMargin
    )
  )

  check(
    "doc-static-scala",
    """
      |import scala.concurrent.Future
      |
      |class A {
      |  <<F@@uture>>.apply("");
      |}
      |""".stripMargin,
    """|```scala
       |object scala.concurrent.Future
       |```
       |
       |Future companion object.
       |""".stripMargin,
    compat = Map(
      "3" -> """|```scala
                |object Future: scala.concurrent
                |```
                |Future companion object.
                |""".stripMargin
    )
  )

  check(
    "doc-case-scala",
    """
      |import scala.concurrent.Future
      |
      |class A {
      |  <<S@@ome>>.apply("");
      |}
      |""".stripMargin,
    """|```scala
       |object scala.Some
       |```
       |
       |Class `Some[A]` represents existing values of type
       | `A`.
       |""".stripMargin,
    compat = Map(
      "3" -> """|```scala
                |final case class Some: Some
                |```
                |Class `Some[A]` represents existing values of type
                | `A`.
                |""".stripMargin
    )
  )

  check(
    "basic-plaintext",
    """|
       |/** 
       |  * Some docstring
       |  */
       |case class Alpha(x: Int) {
       |}
       |
       |object Main {
       |  val x = <<Alp@@ha(2)>>
       |}
       |""".stripMargin,
    """|def apply(x: Int): Alpha
       |
       |Some docstring
       |
       |""".stripMargin,
    contentType = HoverContentType.PLAINTEXT
  )
}
