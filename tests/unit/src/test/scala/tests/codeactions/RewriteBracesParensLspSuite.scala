package tests.codeactions

import scala.meta.internal.metals.codeactions.AddingBracesCodeAction
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
        |${ExtractValueCodeAction.title}
        |${ConvertToNamedArguments.title("foo")}""".stripMargin,
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
       |  List(1,2).foreach ( a => <<>>a )
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("foreach")}
        |${ConvertToNamedArguments.title("List(1,2).foreach")}""".stripMargin,
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach { a => a }
       |}
       |""".stripMargin
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
        |${ConvertToNamedArguments.title("x.foreach")}""".stripMargin,
    """|object Main {
       |  val x = List(1, 2, 3)
       |  x.foreach{_ match {
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
    s"""|${RewriteBracesParensCodeAction.toParens("foo")}
        |${ExtractValueCodeAction.title}""".stripMargin,
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
       |  List(1,2).foreach { a => <<>>a }
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toParens("foreach"),
    """|object Main {
       |  var x = 0
       |  List(1,2).foreach ( a => a )
       |}
       |""".stripMargin
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
    selectedActionIndex = 1
  )

  check(
    "to-parens-noop",
    """|object Main {
       |  var x = 0
       |
       |  List(1,2).foreach {
       |    a =>
       |      a + 1
       |      println(a)
       |      <<>>a
       |  }
       |}
       |""".stripMargin,
    s"${AddingBracesCodeAction.goBraceFul("block")}",
    """|object Main {
       |  var x = 0
       |
       |  List(1,2).foreach {
       |    a =>{
       |      a + 1
       |      println(a)
       |      a
       |    }
       |  }
       |}
       |""".stripMargin
  )
}
