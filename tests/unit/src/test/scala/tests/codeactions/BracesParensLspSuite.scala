package tests.codeactions

import scala.meta.internal.metals.codeactions.PatternMatchRefactor
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class BracesParensLspSuite
    extends BaseCodeActionLspSuite("bracesParensRewrite") {

  check(
    "to-braces-1",
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo(<<>>5)
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toBraces,
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo{5}
       |}
       |""".stripMargin
  )

  check(
    "to-braces-2",
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
    "to-braces-3",
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.map(_ ma<<>>tch {
       |    case 1 => 0
       |    case _ => 1
       |  })
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toBraces,
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.map{_ match {
       |    case 1 => 0
       |    case _ => 1
       |  }}
       |}
       |""".stripMargin
  )

  check(
    "to-parens-1",
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo{<<>>5}
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toParens,
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo(5)
       |}
       |""".stripMargin
  )

  check(
    "to-parens-2",
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
    "to-parens-3",
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.map{_ ma<<>>tch {
       |    case 1 => 0
       |    case _ => 1
       |  }}
       |}
       |""".stripMargin,
    s"""|${PatternMatchRefactor.convertPatternMatch}
        |${RewriteBracesParensCodeAction.toParens}""".stripMargin,
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.map(_ match {
       |    case 1 => 0
       |    case _ => 1
       |  })
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "to-parens-noop",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a =>
       |    println(a)
       |    <<>>a 
       |  }
       |}
       |""".stripMargin,
    "",
    """|object Main {
       |  var x = 0
       |  List(1,2).map { a =>
       |    println(a)
       |    a 
       |  }
       |}
       |""".stripMargin
  )
}
