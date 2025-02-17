package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction
import scala.meta.internal.metals.codeactions.PatternMatchRefactor
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class RewriteBracesParensLspSuite
    extends BaseCodeActionLspSuite("rewriteBracesParens") {

  check(
    "to-braces-1",
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo(<<>>5)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("foo")}
        |${ExtractValueCodeAction.title("5")}
        |${ConvertToNamedArguments.title("foo(...)")}""".stripMargin,
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo{5}
       |}
       |""".stripMargin,
  )

  check(
    "to-braces-2",
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach ( a => <<>>a )
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("foreach")}
        |${ConvertToNamedArguments.title("foreach(...)")}""".stripMargin,
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach { a => a }
       |}
       |""".stripMargin,
  )

  check(
    "to-braces-3",
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.foreach(_ ma<<>>tch {
       |    case 1 => 0
       |    case _ => 1
       |  })
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("foreach")}
        |${ConvertToNamedArguments.title("foreach(...)")}""".stripMargin,
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.foreach{_ match {
       |    case 1 => 0
       |    case _ => 1
       |  }}
       |}
       |""".stripMargin,
  )

  check(
    "to-parens-1",
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo{<<>>5}
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("foo")}
        |${ExtractValueCodeAction.title("{5}")}""".stripMargin,
    """|object Main {
       |  def foo(n: Int) = ???
       |  foo(5)
       |}
       |""".stripMargin,
  )

  check(
    "to-parens-2",
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach { a => <<>>a }
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toParens("foreach"),
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach ( a => a )
       |}
       |""".stripMargin,
  )

  check(
    "to-parens-3",
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.foreach{_ ma<<>>tch {
       |    case 1 => 0
       |    case _ => 1
       |  }}
       |}
       |""".stripMargin,
    s"""|${PatternMatchRefactor.convertPatternMatch}
        |${RewriteBracesParensCodeAction.toParens("foreach")}""".stripMargin,
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.foreach(_ match {
       |    case 1 => 0
       |    case _ => 1
       |  })
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  checkNoAction(
    "to-parens-noop",
    """|object Main {
       |  var x = 0
       |
       |  List(1,2).foreach { a =>
       |    println(a)
       |    <<>>a
       |  }
       |}
       |""".stripMargin,
  )
}
