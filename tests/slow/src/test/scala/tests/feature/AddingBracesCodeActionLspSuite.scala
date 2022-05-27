package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions._

import tests.codeactions.BaseCodeActionLspSuite

class AddingBracesCodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  check(
    "braceless-to-braceful-object",
    """|object Main :<<>>
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(i)
       |  }
       |end Main
       |
       |class A
       |""".stripMargin,
    s"""|${AddingBracesCodeAction.goBraceFul("object definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(i)
       |  }
       |}
       |
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
    s"""|${InsertInferredType.insertType}
        |${AddingBracesCodeAction.goBraceFul("def definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) ={
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin,
    1
  )

  check(
    "braceless-to-braceful-while",
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) = {
       |    whi<<>>le ( 2 > 7 ) do
       |      println("2")
       |      println("5")
       |
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${AddingBracesCodeAction.goBraceFul("while expression")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) = {
       |    while ( 2 > 7 ) {
       |      println("2")
       |      println("5")
       |    }
       |
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin
  )
}
