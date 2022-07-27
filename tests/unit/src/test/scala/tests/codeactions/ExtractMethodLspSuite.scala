package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.ExtractMethodCodeAction

class ExtractMethodLspSuite
    extends BaseCodeActionLspSuite("extractMethodRewrite") {

  check(
    "simple-expr",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  val a = <<123 + method(b)>>
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  def newMethod(): Int =
        |    123 + method(b)
        |
        |  val a = newMethod()
        |}""".stripMargin,
  )

  check(
    "no-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(b, 10)>>
        |  }
        |
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(): Int = {
        |    val b = 2
        |    123 + method(b, 10)
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod()
        |  }
        |
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "single-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(c, 10)>>
        |  }
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(c: Int): Int = {
        |    val b = 2
        |    123 + method(c, 10)
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "name-gen",
    s"""|object A{
        |  def newMethod() = 1
        |  def newMethod0(a: Int) = a + 1
        |  def method(i: Int) = i + i
        |  val a = <<method(5)>>
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("object `A`")}
        |${ConvertToNamedArguments.title("method(...)")}
        |""".stripMargin,
    s"""|object A{
        |  def newMethod() = 1
        |  def newMethod0(a: Int) = a + 1
        |  def method(i: Int) = i + i
        |  def newMethod1(): Int =
        |    method(5)
        |
        |  val a = newMethod1()
        |}""".stripMargin,
  )

  check(
    "multi-param",
    s"""|object A{
        |  val b = 4
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  val a = { 
        |    val c = 5
        |    <<123 + method(c, b) + method(b,c)>>
        |  }
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  def newMethod(c: Int): Int =
        |    123 + method(c, b) + method(b,c)
        |
        |  val a = { 
        |    val c = 5
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "higher-scope",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    def f() = {
        |      val c = 1
        |      <<val d = 3
        |      method(d, b, c)>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method `f`")}
        |${ExtractMethodCodeAction.title("val `a`")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    def newMethod(c: Int): Int = {
        |      val d = 3
        |      method(d, b, c)
        |    }
        |    def f() = {
        |      val c = 1
        |      newMethod(c)
        |    }
        |  }
        |}""".stripMargin,
    selectedActionIndex = 1,
  )

}
