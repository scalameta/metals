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
       |  List(1,2).map ( a => <<>>a )
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${ConvertToNamedArguments.title("List(1,2).map")}""".stripMargin,
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
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${ConvertToNamedArguments.title("x.map")}""".stripMargin,
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
       |  List(1,2).map { a => <<>>a }
       |}
       |""".stripMargin,
    RewriteBracesParensCodeAction.toParens("map"),
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
        |${RewriteBracesParensCodeAction.toParens("map")}""".stripMargin,
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
