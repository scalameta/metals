package tests.codeactions

import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class BracesParensLspSuite
    extends BaseCodeActionLspSuite("implementAbstractMembers") {

  check(
    "to-braces",
    """|object Main {
       |  var x = 0
       |  List(1,2).map ( a => <<>>a )
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toBraces,
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a => a }
       |}
       |""".stripMargin
  )

  check(
    "to-parens",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a => <<>>a }
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toParens,
    """|object Main {
       |  var x = 0
       |  List(1,2).map ( a => a )
       |}
       |""".stripMargin
  )

  check(
    "to-parens",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a =>
       |  <<println(a)>>
       |    a 
       |  }
       |}
       |""".stripMargin,
    "",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a =>
       |  println(a)
       |    a 
       |  }
       |}
       |""".stripMargin
  )
}
