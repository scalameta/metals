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

  // checkEdit(
  //   "single-param",
  //   """|object A{
  //      |  val b = 4
  //      |  def method(i: Int) = i + 1
  //      |  val a = <<123 + method(b)>>
  //      |}""".stripMargin,
  //   """|object A{
  //      |  val b = 4
  //      |  def method(i: Int) = i + 1
  //      |  def newMethod(b: Int) = 123 + method(b)
  //      |  val a = newMethod(b)
  //      |}""".stripMargin,
  // )

  // checkEdit(
  //   "const",
  //   """|object A{
  //      |  val b = 4
  //      |  def method(i: Int, j: Int) = i + j
  //      |  val a = <<123 + method(b, 10)>>
  //      |}""".stripMargin,
  //   """|object A{
  //      |  val b = 4
  //      |  def method(i: Int, j: Int) = i + j
  //      |  def newMethod(b: Int) = 123 + method(b, 10)
  //      |  val a = newMethod(b)
  //      |}""".stripMargin,
  // )

  // checkEdit(
  //   "name-gen",
  //   """|object A{
  //      |  val newMethod = 1
  //      |  def newMethod0(a: Int) = a + 1
  //      |  def method(i: Int) = i + i
  //      |  val a = <<method(5)>>
  //      |}""".stripMargin,
  //   """|object A{
  //      |  val newMethod = 1
  //      |  def newMethod0(a: Int) = a + 1
  //      |  def method(i: Int) = i + i
  //      |  def newMethod1() = method(5)
  //      |  val a = newMethod1()
  //      |}""".stripMargin,
  // )

  checkEdit(
      "single-param",
      """|object A{
         |  val b = 4
         |  def method(i: Int) = i + 1
         |  def newMethod() = 123 + method(b)
         |  val a =<< >>newMethod()
         |}""".stripMargin,
      """|object A{
         |  val b = 4
         |  def method(i: Int) = i + 1
         |  def newMethod(b: Int) = 123 + method(b)
         |  val a = newMethod(b)
         |}""".stripMargin,
    )

    checkEdit(
        "multi-param",
        """|object A{
           |  val b = 4
           |  val c = 3
           |  def method(i: Int, j: Int) = i + 1
           |  def newMethod() = 123 + method(c, b) + method(b,c)
           |  val a =<< >>newMethod()
           |}""".stripMargin,
        """|object A{
           |  val b = 4
           |  val c = 3
           |  def method(i: Int, j: Int) = i + 1
           |  def newMethod(b: Int, c: Int) = 123 + method(c, b) + method(b,c)
           |  val a = newMethod(b, c)
           |}""".stripMargin,
      )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {
      val edits = getAutoImplement(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .extractMethod(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
