package tests.hover

import tests.pc.BaseHoverSuite

class HoverDefnSuite extends BaseHoverSuite {

  check(
    "val",
    """object a {
      |  <<val @@x = List(1)>>
      |}
      |""".stripMargin,
    """|val x: List[Int]
       |""".stripMargin.hover
  )

  check(
    "var",
    """object a {
      |  <<var @@x = List(1)>>
      |}
      |""".stripMargin,
    """|var x: List[Int]
       |""".stripMargin.hover
  )

  check(
    "def-nullary",
    """object a {
      |  <<def @@x = List(1)>>
      |}
      |""".stripMargin,
    """|def x: List[Int]
       |""".stripMargin.hover
  )

  check(
    "def-params",
    """object a {
      |  <<def @@method(x: Int) = List(x)>>
      |}
      |""".stripMargin,
    """|def method(x: Int): List[Int]
       |""".stripMargin.hover
  )

  check(
    "def-tparams",
    """object a {
      |  <<def @@empty[T] = Option.empty[T]>>
      |}
      |""".stripMargin,
    """|def empty[T]: Option[T]
       |""".stripMargin.hover
  )

  check(
    "context-bound",
    """object a {
      |  <<def @@empty[T:Ordering] = Option.empty[T]>>
      |}
      |""".stripMargin,
    """
      |Option[T]
      |def empty[T: Ordering]: Option[T]
      |""".stripMargin.hover
  )

  check(
    "lambda-param",
    """object a {
      |  List(1).map(<<@@x>> => )
      |}
      |""".stripMargin,
    """|```scala
       |x: Int
       |```
       |""".stripMargin
  )

  check(
    "param",
    """object a {
      |  def method(<<@@x: Int>>): Int = x
      |}
      |""".stripMargin,
    """|```scala
       |x: Int
       |```
       |""".stripMargin
  )

  check(
    "ctor",
    """class a {
      |  <<def t@@his(x: Int) = this()>>
      |}
      |""".stripMargin,
    """|```scala
       |def this(x: Int): a
       |```
       |""".stripMargin,
    compat = Map(
      "3.0" -> "def <init>(x: Int): a".hover
    )
  )

  check(
    "ctor-param",
    """class a {
      |  def this(<<@@x: Int>>) = this()
      |}
      |""".stripMargin,
    """|```scala
       |x: Int
       |```
       |""".stripMargin
  )

  check(
    "implicit-param",
    """class a {
      |  def method(implicit <<@@x: Int>>) = this()
      |}
      |""".stripMargin,
    """|```scala
       |implicit x: Int
       |```
       |""".stripMargin
  )

  check(
    "implicit-param2",
    """class a {
      |  def method(implicit y: Int, <<@@x: Int>>) = this()
      |}
      |""".stripMargin,
    """|```scala
       |implicit x: Int
       |```
       |""".stripMargin
  )

  check(
    "object",
    """object M@@yObject
      |""".stripMargin,
    "",
    compat = Map(
      "3.0" -> "object MyObject: `object`".hover
    )
  )

  check(
    "trait",
    """trait M@@yTrait
      |""".stripMargin,
    "",
    compat = Map(
      "3.0" -> "trait MyTrait: MyTrait".hover
    )
  )

  check(
    "class",
    """trait M@@yClass
      |""".stripMargin,
    "",
    compat = Map(
      "3.0" -> "trait MyClass: MyClass".hover
    )
  )

  check(
    "package",
    """package b.p@@kg
      |object Main
      |""".stripMargin,
    """```scala
      |package b.pkg
      |```
      |""".stripMargin,
    automaticPackage = false
  )

  check(
    "pat-bind",
    """
      |object Main {
      |  List(1) match {
      |    case h@@ead :: _ =>
      |  }
      |}
      |""".stripMargin,
    "head: Int".hover,
    compat = Map(
      "3.0" -> "val head: Int".hover
    )
  )

  check(
    "pat-bind2",
    """
      |object Main {
      |  Option(1) match {
      |    case Some(val@@ue) =>
      |  }
      |}
      |""".stripMargin,
    "value: Int".hover,
    compat = Map(
      "3.0" -> "val value: Int".hover
    )
  )

}
