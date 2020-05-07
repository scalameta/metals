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
    "string-selection",
    """|package a
       |
       |object A {
       |  val str = "this <<is>> a string"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|this is a string'''.stripMargin
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
    "composite-string",
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

}
