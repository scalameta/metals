package tests.pc

import tests.BaseCompletionSuite

class SingletonCompletionsSuite extends BaseCompletionSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  check(
    "basic",
    """|val k: 1 = @@
       |""".stripMargin,
    "1: 1",
    topLines = Some(1)
  )

  check(
    "literal",
    """|val k: 1 = 1@@
       |""".stripMargin,
    "1: 1",
    topLines = Some(1)
  )

  check(
    "string",
    """|val k: "aaa" = "@@"
       |""".stripMargin,
    """|"aaa": "aaa"
       |""".stripMargin
  )

  checkEdit(
    "string-edit",
    """|val k: "aaa" = "@@"
       |""".stripMargin,
    """|val k: "aaa" = "aaa"
       |""".stripMargin,
    assertSingleItem = false
  )

  checkEdit(
    "string-edit-2",
    """|val k: "aaa" = @@ //something
       |""".stripMargin,
    """|val k: "aaa" = "aaa" //something
       |""".stripMargin,
    assertSingleItem = false
  )

  check(
    "union",
    """|val k: "aaa" | "bbb" = "@@"
       |""".stripMargin,
    """|"aaa": "aaa" | "bbb"
       |"bbb": "aaa" | "bbb"
       |""".stripMargin
  )

  check(
    "type-alias-union",
    """|type Color = "red" | "green" | "blue"
       |val c: Color = "r@@"
       |""".stripMargin,
    """|"red": Color
       |""".stripMargin
  )

  check(
    "param",
    """|type Color = "red" | "green" | "blue"
       |def paint(c: Color) = ???
       |val _ = paint(@@)
       |""".stripMargin,
    """|"red": Color
       |"green": Color
       |"blue": Color
       |c = : Color
       |""".stripMargin,
    topLines = Some(4)
  )

  check(
    "with-block",
    """|type Color = "red" | "green" | "blue"
       |def c: Color = {
       |  "r@@"
       |}
       |""".stripMargin,
    """|"red": Color
       |""".stripMargin
  )

  check(
    "if-statement",
    """|type Color = "red" | "green" | "blue"
       |def c(shouldBeBlue: Boolean): Color = {
       |  if(shouldBeBlue) "b@@"
       |  else "red"
       |}
       |""".stripMargin,
    """|"blue": Color
       |""".stripMargin
  )

  check(
    "if-statement-2",
    """|type Color = "red" | "green" | "blue"
       |def c(shouldBeBlue: Boolean): Color = {
       |  if(shouldBeBlue) {
       |    println("is blue")
       |    "b@@"
       |  } else "red"
       |}
       |""".stripMargin,
    """|"blue": Color
       |""".stripMargin
  )

  check(
    "if-statement-3",
    """|type Color = "red" | "green" | "blue"
       |def c(shouldBeBlue: Boolean): Color = {
       |  if(shouldBeBlue) {
       |    "b@@"
       |    println("is blue")
       |    "blue"
       |  } else "red"
       |}
       |""".stripMargin,
    """""".stripMargin
  )

  check(
    "middle-of-a-block",
    """|type Color = "red" | "green" | "blue"
       |def c: Color = {
       |  "r@@"
       |  ???
       |}
       |""".stripMargin,
    ""
  )

  check(
    "overloaded",
    """|
       |type Color = "red" | "green" | "blue"
       |def foo(i: Int) = ???
       |def foo(c: Color) = ???
       |
       |def c = foo(@@)
       |""".stripMargin,
    """|c = : Color
       |i = : Int
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "and-type",
    """|type Color = "red" | "green" | "blue" | "black"
       |type FordColor = Color & "black"
       |val i: FordColor = "@@"
       |""".stripMargin,
    """|"black": FordColor
       |""".stripMargin
  )

  check(
    "list",
    """|type Color = "red" | "green" | "blue"
       |val i: List[Color] = List("@@")
       |""".stripMargin,
    """|"red": "red" | "green" | "blue"
       |"green": "red" | "green" | "blue"
       |"blue": "red" | "green" | "blue"
       |""".stripMargin
  )

  check(
    "option",
    """|type Color = "red" | "green" | "blue"
       |val i: Option[Color] = Some("@@")
       |""".stripMargin,
    """|"red": "red" | "green" | "blue"
       |"green": "red" | "green" | "blue"
       |"blue": "red" | "green" | "blue"
       |""".stripMargin
  )

  check(
    "map",
    """|type Color = "red" | "green" | "blue"
       |val i: Option[Int] = Some(1)
       |val g: Option[Color] = i.map { _ => "@@" }
       |""".stripMargin,
    """|"red": "red" | "green" | "blue"
       |"green": "red" | "green" | "blue"
       |"blue": "red" | "green" | "blue"
       |""".stripMargin
  )

  check(
    "some-for-comp",
    """|type Color = "red" | "green" | "blue"
       |val i: Option[Int] = Some(1)
       |val g: Option[Color] =
       |  for
       |    _ <- i
       |  yield "@@"
       |""".stripMargin,
    """|"red": "red" | "green" | "blue"
       |"green": "red" | "green" | "blue"
       |"blue": "red" | "green" | "blue"
       |""".stripMargin
  )

  check(
    "some-for-comp-1",
    """|type Color = "red" | "green" | "blue"
       |val i: Option[Int] = Some(1)
       |val g: Option[Color] =
       |  for
       |    _ <- i
       |    _ <- i
       |    if i > 2
       |  yield "@@"
       |""".stripMargin,
    """|"red": "red" | "green" | "blue"
       |"green": "red" | "green" | "blue"
       |"blue": "red" | "green" | "blue"
       |""".stripMargin
  )

  check(
    "lambda",
    """|def m =
       |  val j = (f: "foo") => 1
       |  j("f@@")
       |""".stripMargin,
    """|"foo": "foo"
       |""".stripMargin
  )

  check(
    "match-case",
    """|val h: "foo" =
       |  1 match
       |    case _ => "@@"
       |""".stripMargin,
    """|"foo": "foo"
       |""".stripMargin
  )

  check(
    "dont-show-on-select",
    """|val f: "foo" = List(1,2,3).@@
       |""".stripMargin,
    "",
    filter = _ == "\"foo\": \"foo\""
  )

}
