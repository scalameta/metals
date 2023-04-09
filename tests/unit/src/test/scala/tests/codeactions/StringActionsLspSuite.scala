package tests.codeactions

import scala.meta.internal.metals.codeactions.StringActions

class StringActionsLspSuite extends BaseCodeActionLspSuite("stringActions") {

  check(
    "empty-string-multiline",
    """|package a
       |
       |object A {
       |  val str = <<"">>
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = '''|'''.stripMargin
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "empty-string-interpolation",
    """|package a
       |
       |object A {
       |  val str = <<"">>
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = s""
       |}
       |""".stripMargin.replace("'", "\""),
    selectedActionIndex = 1,
  )

  check(
    "multi-strings-one-line-multiline",
    """|package a
       |
       |object A {
       |  val str = <<"">> + ""
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = '''|'''.stripMargin + ""
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "multi-strings-one-line-interpolation",
    """|package a
       |
       |object A {
       |  val str = <<"">> + ""
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = s"" + ""
       |}
       |""".stripMargin.replace("'", "\""),
    selectedActionIndex = 1,
  )

  check(
    "multi-strings-multiline",
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = "this <<is>> a string"
       |  val d = "hello"
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = '''|this is a string'''.stripMargin
       |  val d = "hello"
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "multi-strings-interpolation",
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = "this $ <<is>> a string"
       |  val d = "hello"
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val e = "hello"
       |  val c = s"this $$ is a string"
       |  val d = "hello"
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "multiline-string-interpolation",
    """|package a
       |
       |object A {
       |  val c = '''hello
       |  this $ <<is>> a string
       |  '''
       |}
       |""".stripMargin.replace("'", "\""),
    s"${StringActions.interpolationTitle}",
    """|package a
       |
       |object A {
       |  val c = s'''hello
       |  this $$ is a string
       |  '''
       |}
       |""".stripMargin.replace("'", "\""),
  )

  checkNoAction(
    "out-selection-no-codeAction",
    """|package a
       |
       |object A {
       |  val <<str>>: String = "this is a string"
       |}
       |""".stripMargin,
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
    s"${StringActions.multilineTitle}",
    """|package a
       |
       |object A {
       |  val other = "text"
       |  val str = s'''|this is an ${other} string'''.stripMargin
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "mix-strings-one-line-multiline",
    """|package a
       |
       |object A {
       |  val str = s"Hello " + " the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = s"Hello " + '''| the cursor is actually here '''.stripMargin
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "mix-strings-one-line-interpolation",
    """|package a
       |
       |object A {
       |  val str = s"Hello " + " the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = s"Hello " + s" the cursor is actually here "
       |}
       |""".stripMargin.replace("'", "\""),
    selectedActionIndex = 1,
  )

  check(
    "remix-strings-one-line-multiline",
    """|package a
       |
       |object A {
       |  val str = "Hello" + s" the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.removeInterpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = "Hello" + s'''| the cursor is actually here '''.stripMargin
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "remix-strings-one-line-remove-interpolation",
    """|package a
       |
       |object A {
       |  val str = "Hello" + s" the <<cursor>> is actually here "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.removeInterpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val str = "Hello" + " the cursor is actually here "
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "triple-quotes-remove-interpolation",
    """|package a
       |
       |object A {
       |  val str = s'''this <<is a>> string'''
       |}
       |""".stripMargin.replace("'", "\""),
    s"${StringActions.removeInterpolationTitle}",
    """|package a
       |
       |object A {
       |  val str = '''this is a string'''
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "triple-quotes-remove-interpolation-custom",
    """|package a
       |
       |object A {
       |  implicit class ExtStringContext(sc: StringContext) {
       |     def foo(args: Any*): String = ???
       |  }
       |  val str = foo'''this <<is a>> string'''
       |}
       |""".stripMargin.replace("'", "\""),
    s"${StringActions.removeInterpolationTitle}",
    """|package a
       |
       |object A {
       |  implicit class ExtStringContext(sc: StringContext) {
       |     def foo(args: Any*): String = ???
       |  }
       |  val str = '''this is a string'''
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "mix-strings-multiline",
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = "this <<is>> a string"
       |  val d = s"hello ${} "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = '''|this is a string'''.stripMargin
       |  val d = s"hello ${} "
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "mix-strings-interpolation",
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = "this <<is>> a string"
       |  val d = s"hello ${} "
       |}
       |""".stripMargin,
    s"""|${StringActions.multilineTitle}
        |${StringActions.interpolationTitle}""".stripMargin,
    """|package a
       |
       |object A {
       |  val e = s"hello ${} "
       |  val c = s"this is a string"
       |  val d = s"hello ${} "
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
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
    s"${StringActions.multilineTitle}",
    """|package a
       |
       |object A {
       |  val c = "this is a string"
       |  val e = s'''|hello ${} '''.stripMargin
       |  val d = s"hello ${} "
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "triple-quotes",
    """|package a
       |
       |object A {
       |  val str = <<'''>>'''
       |}
       |""".stripMargin.replace("'", "\""),
    s"${StringActions.interpolationTitle}",
    """|package a
       |
       |object A {
       |  val str = s''''''
       |}
       |""".stripMargin.replace("'", "\""),
  )

  checkNoAction(
    "triple-quotes-interpolation-no-codeAction",
    """|package a
       |
       |object A {
       |  val str = s'''this <<is a>> ${} string'''
       |}
       |""".stripMargin.replace("'", "\""),
  )

  check(
    "mix-triple-quotes",
    """|package a
       |
       |object A {
       |  val str = s'''|multiline'''.stripMargin + '''an <<other>> multiline'''
       |}
       |""".stripMargin.replace("'", "\""),
    s"${StringActions.interpolationTitle}",
    """|package a
       |
       |object A {
       |  val str = s'''|multiline'''.stripMargin + s'''an other multiline'''
       |}
       |""".stripMargin.replace("'", "\""),
  )

}
