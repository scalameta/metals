package tests.hover

import tests.pc.BaseHoverSuite

class HoverDocSuite extends BaseHoverSuite {
  override def requiresJdkSources: Boolean = true

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
      "3.0" -> "def emptyList[T]: java.util.List[T]".hover
    )
  )

}
