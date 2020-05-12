package tests.codeactions

import scala.meta.internal.metals.codeactions.StringActions

class StringActionsLspSuite extends BaseCodeActionLspSuite("stringActions") {

  check(
    "empty-string",
    """|package a
       |
       |object A {
       |  val str = <<"">>
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|'''.stripMargin
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "multi-strings-one-line",
    """|package a
       |
       |object A {
       |  val str = <<"">> + ""
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|'''.stripMargin + ""
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "multi-strings",
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = "this <<is>> a string"
       |  val d = "hello"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = '''|this is a string'''.stripMargin
       |  val d = "hello"
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "out-selection-no-codeAction",
    """|package a
       |
       |object A {
       |  val <<str>> = "this is a string"
       |}
       |""".stripMargin,
    "",
    """|package a
       |
       |object A {
       |  val str = "this is a string"
       |}
       |""".stripMargin
  )

  check(
    "interpolation-string",
    """|package a
       |
       |object A {
       |  val other = "text"
       |  val str = s"this <<is>> an ${other} string"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val other = "text"
       |  val str = s'''|this is an ${other} string'''.stripMargin
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "mix-strings-one-line",
    """|package a
       |
       |object A {
       |  val str = s"Hello " + " the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = s"Hello " + '''| the cursor is actually here '''.stripMargin
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "remix-strings-one-line",
    """|package a
       |
       |object A {
       |  val str = "Hello" + s" the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = "Hello" + s'''| the cursor is actually here '''.stripMargin
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "mix-strings",
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = "this <<is>> a string"
       |  val d = s"hello ${} "
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = '''|this is a string'''.stripMargin
       |  val d = s"hello ${} "
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "remix-strings",
    """|package a
       |
       |object A {
       |  val c = "this is a string"
       |  val e = s"he<<llo>> ${} "
       |  val d = s"hello ${} "
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val c = "this is a string"
       |  val e = s'''|hello ${} '''.stripMargin
       |  val d = s"hello ${} "
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "triple-quotes-no-codeAction",
    """|package a
       |
       |object A {
       |  val str = <<'''>>'''
       |}
       |""".stripMargin.replace("'", "\""),
    "",
    """|package a
       |
       |object A {
       |  val str = ''''''
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "triple-quotes-interpolation-no-codeAction",
    """|package a
       |
       |object A {
       |  val str = s'''this <<is a>> string'''
       |}
       |""".stripMargin.replace("'", "\""),
    "",
    """|package a
       |
       |object A {
       |  val str = s'''this is a string'''
       |}
       |""".stripMargin.replace("'", "\"")
  )

  check(
    "mix-triple-quotes-no-codeAction",
    """|package a
       |
       |object A {
       |  val str = s'''|multiline'''.stripMargin + '''an <<other>> multiline'''
       |}
       |""".stripMargin.replace("'", "\""),
    "",
    """|package a
       |
       |object A {
       |  val str = s'''|multiline'''.stripMargin + '''an other multiline'''
       |}
       |""".stripMargin.replace("'", "\"")
  )

}
