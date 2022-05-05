package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions._

import tests.codeactions.BaseCodeActionLspSuite

class BracingSwitchCodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  check(
    "braceful-to-braceless-object",
    """|object Ma<<>>in {
       |  def method2(i: Int) = ???
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("object", "Main")}
        |${BracelessBracefulSwitchCodeAction.goBraceless("object definition")}""".stripMargin,
    """|object Main :
       |  def method2(i: Int) = ???
       |
       |class A
       |""".stripMargin
  )

  check(
    "braceless-to-braceful-object",
    """|object Ma<<>>in :
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i)
       |end Main
       |
       |class A
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("object", "Main")}
        |${BracelessBracefulSwitchCodeAction.goBraceFul("object definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i)
       |}
       |
       |class A
       |""".stripMargin
  )

  check(
    "braceless-to-braceful-method",
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def ma<<>>in(i : Int) =
       |    val newValue = i + 23
       |    method2(newValue)
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBraceFul("def definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) = {
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin
  )

  check(
    "braceful-to-braceless-method",
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def ma<<>>in(i : Int) = {
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBraceless("def definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) =
       |    val newValue = i + 23
       |    method2(newValue)
       |
       |}
       |
       |class A
       |""".stripMargin
  )
}
