package tests.codeactions

import scala.meta.internal.metals.codeactions.StringActions

class MultlineStringLspSuite extends BaseCodeActionLspSuite("multilineString") {

  check(
    "empty-string",
    """|package a
       |
       |object A {
       |  val str = "<<>>"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|'''.stripMargin
       |}
       |""".stripMargin.replace("'", """"""")
  )

  check(
    "string-selection",
    """|package a
       |
       |object A {
       |  val str = <<"this is a string">>
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|this is a string'''.stripMargin
       |}
       |""".stripMargin.replace("'", """"""")
  )

  check(
    "narrow-selection",
    """|package a
       |
       |object A {
       |  val str = "this i<<s >>a string"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val str = '''|this is a string'''.stripMargin
       |}
       |""".stripMargin.replace("'", """"""")
  )

  check(
    "interpolation-string",
    """|package a
       |
       |object A {
       |  val text = "text"
       |  val str = s"<<this>> is an interpolation ${text}"
       |}
       |""".stripMargin,
    s"${StringActions.title}",
    """|package a
       |
       |object A {
       |  val text = "text"
       |  val str = s'''|this is an interpolation ${text}'''.stripMargin
       |}
       |""".stripMargin.replace("'", """"""")
  )

}
