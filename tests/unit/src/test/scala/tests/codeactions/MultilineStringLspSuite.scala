package tests.codeactions

import scala.meta.internal.metals.codeactions.StringToMultiline

class MultlineStringLspSuite extends BaseCodeActionLspSuite("multilineString") {

  check(
    "empty-string",
    """|package a
       |
       |object A {
       |  val str = "<<>>"
       |}
       |""".stripMargin,
    s"${StringToMultiline.title}",
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
    s"${StringToMultiline.title}",
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
    s"${StringToMultiline.title}",
    """|package a
       |
       |object A {
       |  val str = '''|this is a string'''.stripMargin
       |}
       |""".stripMargin.replace("'", """"""")
  )

}
