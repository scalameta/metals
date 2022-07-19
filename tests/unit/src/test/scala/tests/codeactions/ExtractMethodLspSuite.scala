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
    s"""|${ExtractMethodCodeAction.title("123 + method(b)")}""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  def newMethod(b: Int): Int = 123 + method(b)
       |  val a = newMethod(b)
       |}""".stripMargin,
  )

  check(
    "const",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  val a = 123 + <<method(b, 10)>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(b, 10)")}
        |${ConvertToNamedArguments.title("method(...)")}""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  def newMethod(b: Int): Int = method(b, 10)
       |  val a = 123 + newMethod(b)
       |}""".stripMargin,
  )

  check(
    "name-gen",
    """|object A{
       |  def newMethod() = 1
       |  def newMethod0(a: Int) = a + 1
       |  def method(i: Int) = i + i
       |  val a = <<method(5)>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(5)")}
        |${ConvertToNamedArguments.title("method(...)")}""".stripMargin,
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
       |  val a = <<123 + method(c, b) + method(b,c)>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("123 + method(c,b) + method(b,c)")}""".stripMargin,
    """|object A{
       |  val b = 4
       |  val c = 3
       |  def method(i: Int, j: Int) = i + 1
       |  def newMethod(b: Int, c: Int): Int = 123 + method(c, b) + method(b,c)
       |  val a = newMethod(b, c)
       |}""".stripMargin,
  )
  check(
    "wrong-param",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i * j
       |  val a = 123 + <<method(b, List(1,2,3))>>
       |}""".stripMargin,
    s"""|${ExtractMethodCodeAction.title("method(b, List(1,2,3))")}
        |${ConvertToNamedArguments.title("method(...)")}""".stripMargin,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i * j
       |  def newMethod(b: Int): Int = method(b, List(1,2,3))
       |  val a = 123 + newMethod(b)
       |}""".stripMargin,
  )
}
