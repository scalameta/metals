package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class ExtractMethodSuite extends BaseCodeActionSuite {

  // override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
  //   IgnoreScala2
  // )

  checkEdit(
    "single-param",
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  val a = <<123 + method(b)>>
       |}""".stripMargin,
    0,
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  def newMethod(): Int = 123 + method(b)
       |  val a = newMethod()
       |}""".stripMargin,
  )

  checkEdit(
    "const",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  val a = 123 + <<method(b, 10)>>
       |}""".stripMargin,
    0,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  def newMethod(): Int = method(b, 10)
       |  val a = 123 + newMethod()
       |}""".stripMargin,
  )

  checkEdit(
    "name-gen",
    """|object A{
       |  def newMethod() = 1
       |  def newMethod0(a: Int) = a + 1
       |  def method(i: Int) = i + i
       |  val a = <<method(5)>>
       |}""".stripMargin,
    0,
    """|object A{
       |  def newMethod() = 1
       |  def newMethod0(a: Int) = a + 1
       |  def method(i: Int) = i + i
       |  def newMethod1(): Int = method(5)
       |  val a = newMethod1()
       |}""".stripMargin,
  )

  checkEdit(
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
    1,
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
  )

  checkEdit(
    "wrong-param".tag(IgnoreScala2),
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i * j
       |  val a = 123 + <<method(b, List(1,2,3))>>
       |}""".stripMargin,
    0,
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i * j
       |  def newMethod(): Int = method(b, List(1,2,3))
       |  val a = 123 + newMethod()
       |}""".stripMargin,
  )

  checkEdit(
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
    1,
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
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      lv: Int,
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {

      val edits = getAutoImplement(original, lv)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      lv: Int,
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val targetRegex = "<<(.+)>>".r
    val target = targetRegex.findAllMatchIn(original).toList match {
      case Nil => fail("Missing <<target>>")
      case t :: Nil => t.group(1)
      case _ => fail("Multiple <<targets>> found")
    }
    val code2 = original.replace("<<", "").replace(">>", "")
    val offset = original.indexOf("<<")
    val applRange = target.length()
    val result = presentationCompiler
      .extractMethod(
        CompilerOffsetParams(URI.create(filename), code2, offset, cancelToken),
        applRange,
        lv,
      )
      .get()
    result.asScala.toList
  }

}
