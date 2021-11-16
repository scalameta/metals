package tests.codeactions

import scala.meta.internal.metals.codeactions.ExtractValueCodeAction

class ExtractValueLspSuite
    extends BaseCodeActionLspSuite("extractValueRewrite") {

  check(
    "block",
    """|object Main {
       |  def method2(i: Int) = ???
       |  def method1(s: String): Unit = {
       |    method2(1 + <<2>>)
       |  }
       |
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def method1(s: String): Unit = {
       |    val newValue = 1 + 2
       |    method2(newValue)
       |  }
       |
       |}
       |""".stripMargin
  )

  check(
    "for-comprehension",
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main() = {
       |    val opt = Option(1)
       |    for {
       |       i <- opt
       |       res = method2(i + 23 + <<123>>)      
       |    } yield res
       |  }
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main() = {
       |    val opt = Option(1)
       |    for {
       |       i <- opt
       |       newValue = i + 23 + 123
       |       res = method2(newValue)      
       |    } yield res
       |  }
       |}
       |""".stripMargin
  )

  check(
    "single-def",
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(i + 23 + <<123>>)
       |  }
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = {
       |      val newValue = i + 23 + 123
       |      method2(newValue)
       |    }
       |  }
       |}
       |""".stripMargin
  )

  check(
    "single-def-split",
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |    method2(i + 23 + <<123>>)
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) = {
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |  }
       |}
       |""".stripMargin
  )

  check(
    "multiple-apply",
    """|object Main {
       |  def method2(i: Int) : Int = ???
       |  def method1(s: String): Unit = {
       |    println("Hello!"); method2(1  + method2(1 + <<2>>))
       |  }
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) : Int = ???
       |  def method1(s: String): Unit = {
       |    val newValue = 1 + 2
       |    println("Hello!"); method2(1  + method2(newValue))
       |  }
       |}
       |""".stripMargin
  )

  check(
    "template-existing-names",
    """|object Main {
       |  def method2(i: Int) = ???
       |  val (newValue, newValue1) = (0, 1)
       |  def newValue0 = ???
       |  method2({ 1 + <<2>> })
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  val (newValue, newValue1) = (0, 1)
       |  def newValue0 = ???
       |  val newValue2 = { 1 + 2 }
       |  method2(newValue2)
       |}
       |""".stripMargin
  )

  check(
    "tabs",
    s"""|object Main {
        |\tdef method2(i: Int) = ???
        |\tmethod2(<<List>>(1, 2, 3).map(_ + 1).sum)
        |}
        |""".stripMargin,
    ExtractValueCodeAction.title,
    s"""|object Main {
        |\tdef method2(i: Int) = ???
        |\tval newValue = List(1, 2, 3).map(_ + 1).sum
        |\tmethod2(newValue)
        |}
        |""".stripMargin
  )

}
