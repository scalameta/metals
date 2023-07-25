package tests.hover

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
           |""".stripMargin,
    ),
  )

  check(
    "doc-parent",
    """object a {
      |  <<List(12).hea@@dOption>>
      |}
      |""".stripMargin,
    // Assert that the docstring is extracted.

    """|```scala
       |override def headOption: Option[Int]
       |```
       |Optionally selects the first element.
       | Note: might return different results for different runs, unless the underlying collection type is ordered.
       |
       |**Returns:** the first element of this iterable collection if it is nonempty,
       |          `None` if it is empty.
       |""".stripMargin,
    compat = Map(
      "2.12" ->
        """|```scala
           |def headOption: Option[Int]
           |```
           |Optionally selects the first element.
           | $orderDependent
           |
           |**Returns:** the first element of this traversable collection if it is nonempty,
           |          `None` if it is empty.
           |""".stripMargin
    ),
  )

  check(
    "java-method".tag(IgnoreScalaVersion(_ => isJava8)),
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

  check(
    "case-class",
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
       |""".stripMargin,
  )

  check(
    "case-class1",
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
       |""".stripMargin,
  )

  check(
    "case-class2",
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
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
       |""".stripMargin,
  )

}
