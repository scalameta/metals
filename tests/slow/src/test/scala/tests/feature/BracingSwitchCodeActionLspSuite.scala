package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions._

import tests.codeactions.BaseCodeActionLspSuite

class BracingSwitchCodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  check(
    "braceful-to-braceless-object",
    """|object Main {<<>>
       |  def method2(i: Int) = ???
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBracelessWithFormatting("object definition")}""".stripMargin,
    """|object Main :
       |  def method2(i: Int) = ???
       |
       |
       |class A
       |""".stripMargin
  )

  check(
    "braceless-to-braceful-object",
    """|object Main :<<>>
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i)
       |end Main
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBraceFul("object definition")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i)
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
        |${BracelessBracefulSwitchCodeAction.goBraceFul("def definition")}""".stripMargin,
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
    s"""|${InsertInferredType.insertType}
        |${BracelessBracefulSwitchCodeAction.goBracelessWithFormatting("def definition")}""".stripMargin,
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
       |""".stripMargin,
    1
  )

  check(
    "braceful-to-braceless-if",
    """|object Main {
       |          def method2(i: Int) = ???
       |
       |  def main(i : Int) = {
       |    i<<>>f ( 2 > 7 ) {
       |      2 + 2
       |      5 + 5
       |    } else {
       |      println("6")
       |    }
       |      val newValue = i + 23
       |    method2(newValue)
       |  }
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBracelessWithFormatting("then expression")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) = {
       |    if ( 2 > 7 ) then
       |      2 + 2
       |      5 + 5
       |    else {
       |      println("6")
       |    }
       |    val newValue = i + 23
       |    method2(newValue)
       |  }
       |
       |}
       |
       |class A
       |""".stripMargin
  )

  check(
    "braceful-to-braceless-catch",
    """|object
       |Main {
       |    def method2(i: Int) = ???
       |
       |
       |
       |    def main(i :      Int) = {
       |                        try {
       |        println("1")
       |      } ca<<>>tch {
       |          case _ => println("2")
       |      }
       |      finally
       |          println("3")
       |          println("4")
       |      val newValue = i + 23
       |      method2(newValue)
       |    }
       |
       |}
       |
       |class A
       |""".stripMargin,
    s"""|${BracelessBracefulSwitchCodeAction.goBracelessWithFormatting("catch expression")}""".stripMargin,
    """|object Main {
       |    def method2(i: Int) = ???
       |
       |    def main(i : Int) = {
       |      try {
       |        println("1")
       |      } catch
       |          case _ => println("2")
       |      finally
       |          println("3")
       |          println("4")
       |      val newValue = i + 23
       |      method2(newValue)
       |    }
       |
       |}
       |
       |class A
       |""".stripMargin
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
    s"""|${BracelessBracefulSwitchCodeAction.goBraceFul("while expression")}""".stripMargin,
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
