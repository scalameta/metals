package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
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
    s"""|${ExtractValueCodeAction.title("1 + 2")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def method1(s: String): Unit = {
       |    val newValue = 1 + 2
       |    method2(newValue)
       |  }
       |
       |}
       |""".stripMargin,
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
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
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
       |""".stripMargin,
  )

  check(
    "for-comprehension-head",
    """|object Main {
       |  def method2(i: Int): Option[String]  = ???
       |
       |  def main() = {
       |    val i = 0
       |    for {
       |       res <- method2(i + 23 + <<123>>)
       |    } yield res
       |  }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int): Option[String]  = ???
       |
       |  def main() = {
       |    val i = 0
       |    val newValue = i + 23 + 123
       |    for {
       |       res <- method2(newValue)
       |    } yield res
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "single-def",
    """|object Main {
       |  def method2(param: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(param = i + 23 + <<123>>)
       |  }
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title("i + 23 + 1(...)"),
    """|object Main {
       |  def method2(param: Int) = ???
       |  def main = {
       |    def inner(i : Int) = {
       |      val newValue = i + 23 + 123
       |      method2(param = newValue)
       |    }
       |  }
       |}
       |""".stripMargin,
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
    s"""|${ExtractValueCodeAction.title("i + 23 + 1(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) = ???
       |
       |  def main(i : Int) = {
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "multiple-apply",
    """|object Main {
       |  def method2(i: Int) : Int = ???
       |  def method1(s: String): Unit = {
       |    println("Hello!"); method2(1 + method2(1 + <<2>>))
       |  }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1 + 2")}
        |${ExtractValueCodeAction.title("1 + method2(1 + 2)")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
    """|object Main {
       |  def method2(i: Int) : Int = ???
       |  def method1(s: String): Unit = {
       |    println("Hello!"); 
       |    val newValue = 1 + 2
       |    method2(1 + method2(newValue))
       |  }
       |}
       |""".stripMargin,
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
    ExtractValueCodeAction.title("{ 1 + 2 }"),
    """|object Main {
       |  def method2(i: Int) = ???
       |  val (newValue, newValue1) = (0, 1)
       |  def newValue0 = ???
       |  val newValue2 = { 1 + 2 }
       |  method2(newValue2)
       |}
       |""".stripMargin,
  )

  check(
    "tabs",
    s"""|object Main {
        |\tdef method2(i: Int) = ???
        |\tmethod2(<<List>>(1, 2, 3).map(_ + 1).sum)
        |}
        |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("List(1, 2,(...)")}
        |${ConvertToNamedArguments.title("method2(...)")}""".stripMargin,
    s"""|object Main {
        |\tdef method2(i: Int) = ???
        |\tval newValue = List(1, 2, 3).map(_ + 1).sum
        |\tmethod2(newValue)
        |}
        |""".stripMargin,
  )

  check(
    "extract-if-cond",
    """|object Main{
       |  if(2 > <<3>>) 5 else 4
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("2 > 3")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = 2 > 3
       |  if(newValue) 5 else 4
       |}
       |""".stripMargin,
  )

  check(
    "extract-if-res",
    """|object Main{
       |  if(2 > 3) 5 <<+>> 1 else 4
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("5 + 1")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = 5 + 1
       |  if(2 > 3) newValue else 4
       |}
       |""".stripMargin,
  )

  check(
    "extract-tuple",
    """|object Main{
       |  val a = (1,<<2>>,3)
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("2")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = 2
       |  val a = (1,newValue,3)
       |}
       |""".stripMargin,
  )
  check(
    "extract-match",
    """|object Main{
       |  1 + <<2>> + 3 match {
       |    case _ => 6
       |  }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1 + 2 + 3")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = 1 + 2 + 3
       |  newValue match {
       |    case _ => 6
       |  }
       |}
       |""".stripMargin,
  )
  check(
    "extract-throw",
    """|object Main{
       |  throw new Exce<<p>>tion("message")
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("new Except(...)")}
        |${ConvertToNamedArguments.title("Exception(...)")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = new Exception("message")
       |  throw newValue
       |}
       |""".stripMargin,
  )

  check(
    "extract-while",
    """|object Main{
       |  while(2 > <<3>>) { }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("2 > 3")}
        |""".stripMargin,
    """|object Main{
       |  val newValue = 2 > 3
       |  while(newValue) { }
       |}
       |""".stripMargin,
  )

  check(
    "extract-return",
    """|object Main{
       |  def main(i: Int): Int = {
       |    return <<1>> + 2
       |  }
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1 + 2")}
        |""".stripMargin,
    """|object Main{
       |  def main(i: Int): Int = {
       |    val newValue = 1 + 2
       |    return newValue
       |  }
       |}
       |""".stripMargin,
  )

  check(
    "multiple-apply",
    """|object Main{
       |  def method(a: Int) = a + 1
       |  method(method(meth<<o>>d(5)))
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("method(5)")}
        |${ExtractValueCodeAction.title("method(method(5)")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    """|object Main{
       |  def method(a: Int) = a + 1
       |  val newValue = method(5)
       |  method(method(newValue))
       |}
       |""".stripMargin,
  )

  check(
    "apply-if",
    """|object Main{
       |  def method(a: Int) = a + 1
       |  if(method(<<1>>) > 2) 2 else 3
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1")}
        |${ExtractValueCodeAction.title("method(1) > 2")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    """|object Main{
       |  def method(a: Int) = a + 1
       |  val newValue = 1
       |  if(method(newValue) > 2) 2 else 3
       |}
       |""".stripMargin,
  )

  check(
    "extract-new",
    """|class Car(age: Int)
       |object Main{
       |  val car = new Car(age = <<1>>)
       |}
       |""".stripMargin,
    s"""|${ExtractValueCodeAction.title("1")}
        |""".stripMargin,
    """|class Car(age: Int)
       |object Main{
       |  val newValue = 1
       |  val car = new Car(age = newValue)
       |}
       |""".stripMargin,
  )
  check(
    "multiline-line-block-with-braces",
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue =
       |     aa.map(item => {
       |        m(i<<t>>em + 1)
       |     })
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title("item + 1")}
        |${ConvertToNamedArguments.title("m(...)")}
        |""".stripMargin,
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue =
       |     aa.map(item => {
       |        val newValue = item + 1
       |        m(newValue)
       |     })
       |}""".stripMargin,
  )
  check(
    "one-line-block-with-braces",
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue = aa.map(item => {m(i<<t>>em + 1)})
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title("item + 1")}
        |${ConvertToNamedArguments.title("m(...)")}
        |""".stripMargin,
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue = aa.map(item => {
       |    val newValue = item + 1
       |    m(newValue)})
       |}""".stripMargin,
  )

  check(
    "one-line-block-no-braces",
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue = aa.map(item => m(i<<t>>em + 1))
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title("item + 1")}
        |${ConvertToNamedArguments.title("m(...)")}
        |""".stripMargin,
    """|object Main{
       |  val aa : List[Int] = ???
       |  def m(i: Int) : Int = ???
       |
       |  val newValue = aa.map(item => {
       |    val newValue = item + 1
       |    m(newValue)
       |  })
       |}""".stripMargin,
  )

  check(
    "multiline-line-block-no-braces",
    """|object Main{
       |val aa : List[Int] = ???
       |def m(i: Int) : Int = ???
       |
       |val newValue =
       |  aa.map(item =>
       |    m(<<i>>tem + 1)
       |  )
       |}""".stripMargin,
    s"""|${ExtractValueCodeAction.title("item + 1")}
        |${ConvertToNamedArguments.title("m(...)")}
        |""".stripMargin,
    """|object Main{
       |val aa : List[Int] = ???
       |def m(i: Int) : Int = ???
       |
       |val newValue =
       |  aa.map(item => {
       |    val newValue = item + 1
       |    m(newValue)
       |  }
       |  )
       |}""".stripMargin,
  )

}
