package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.ExtractMethodCodeAction

class ExtractMethodLspSuite
    extends BaseCodeActionLspSuite("extractMethodRewrite") {

  check(
    "single-param",
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  val a = <<123 + method(b)>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("123 + method(b)", "object `A`")}
        |""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  def newMethod(): Int = 123 + method(b)
       |  val a = newMethod()
       |}""".stripMargin,
  )

  check(
    "const",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  val a = {
       |    val b = 2
       |    123 + <<method(b, 10)>>
       |  }
       | 
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(b, 10)", "val `a`")}
        |${ExtractMethodCodeAction.title("method(b, 10)", "object `A`")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  def newMethod(b: Int): Int = method(b, 10)
       |  val a = {
       |    val b = 2
       |    123 + newMethod(b)
       |  }
       | 
       |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "name-gen",
    """|object A{
       |  def newMethod() = 1
       |  def newMethod0(a: Int) = a + 1
       |  def method(i: Int) = i + i
       |  val a = <<method(5)>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(5)", "object `A`")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    """|object A{
       |  def newMethod() = 1
       |  def newMethod0(a: Int) = a + 1
       |  def method(i: Int) = i + i
       |  def newMethod1(): Int = method(5)
       |  val a = newMethod1()
       |}""".stripMargin,
  )

  check(
    "multi-param",
    """|object A{
       |  val b = 4
       |  val c = 3
       |  def method(i: Int, j: Int) = i + 1
       |  val a = { 
       |    val c = 5
       |    <<123 + method(c, b) + method(b,c)>>
       |  }
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("123 + method(c, b) + method(b,c)", "val `a`")}
        |${ExtractMethodCodeAction.title("123 + method(c, b) + method(b,c)", "object `A`")}
        |""".stripMargin,
    """|object A{
       |  val b = 4
       |  val c = 3
       |  def method(i: Int, j: Int) = i + 1
       |  def newMethod(c: Int): Int = 123 + method(c, b) + method(b,c)
       |  val a = { 
       |    val c = 5
       |    newMethod(c)
       |  }
       |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "higher-scope",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + 1
       |  val a = {
       |    def f() = {
       |      val d = 3
       |      <<method(d, b)>>
       |    }
       |  }
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(d, b)", "method `f`")}
        |${ExtractMethodCodeAction.title("method(d, b)", "val `a`")}
        |${ExtractMethodCodeAction.title("method(d, b)", "object `A`")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + 1
       |  val a = {
       |    def newMethod(d: Int): Int = method(d, b)
       |    def f() = {
       |      val d = 3
       |      newMethod(d)
       |    }
       |  }
       |}""".stripMargin,
    selectedActionIndex = 1,
  )
}
