package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite
import scala.meta.internal.metals.CompilerRangeParams

class ExtractMethodSuite extends BaseCodeActionSuite {

  checkEdit(
    "single-param",
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  val a = <<123 + method(b)>>
       |}""".stripMargin,
      (0, 4),
    """|object A{
       |  val b = 4
       |  def method(i: Int) = i + 1
       |  def newMethod(): Int = 
       |  \t123 + method(b)
       |  val a = newMethod()
       |}""".stripMargin,
  )

  checkEdit(
    "const",
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  val a = {
       |    <<val b = 2
       |    123 + method(b, 10)>>
       |  }
       | 
       |}""".stripMargin,
    (0, 9),
    """|object A{
       |  val b = 4
       |  def method(i: Int, j: Int) = i + j
       |  def newMethod(b: Int): Int = {
       |  \tval b = 2
       |  \tmethod(b, 10)
       |  }
       |  val a = {
       |    newMethod()
       |  }
       | 
       |}""".stripMargin,
  )


//   checkEdit(
//     "multi-param",
//     """|object A{
//        |  val b = 4
//        |  val c = 3
//        |  def method(i: Int, j: Int) = i + 1
//        |  val a = { 
//        |    val c = 5
//        |    <<123 + method(c, b) + method(b,c)>>
//        |  }
//        |}""".stripMargin,
//     1,
//     """|object A{
//        |  val b = 4
//        |  val c = 3
//        |  def method(i: Int, j: Int) = i + 1
//        |  def newMethod(c: Int): Int = 123 + method(c, b) + method(b,c)
//        |  val a = { 
//        |    val c = 5
//        |    newMethod(c)
//        |  }
//        |}""".stripMargin,
//   )

//   checkEdit(
//     "wrong-param".tag(IgnoreScala2),
//     """|object A{
//        |  val b = 4
//        |  def method(i: Int, j: Int) = i * j
//        |  val a = 123 + <<method(b, List(1,2,3))>>
//        |}""".stripMargin,
//     0,
//     """|object A{
//        |  val b = 4
//        |  def method(i: Int, j: Int) = i * j
//        |  def newMethod(): Int = method(b, List(1,2,3))
//        |  val a = 123 + newMethod()
//        |}""".stripMargin,
//   )

//   checkEdit(
//     "higher-scope",
//     """|object A{
//        |  val b = 4
//        |  def method(i: Int, j: Int) = i + 1
//        |  val a = {
//        |    def f() = {
//        |      val d = 3
//        |      <<method(d, b)>>
//        |    }
//        |  }
//        |}""".stripMargin,
//     1,
//     """|object A{
//        |  val b = 4
//        |  def method(i: Int, j: Int) = i + 1
//        |  val a = {
//        |    def newMethod(d: Int): Int = method(d, b)
//        |    def f() = {
//        |      val d = 3
//        |      newMethod(d)
//        |    }
//        |  }
//        |}""".stripMargin,
//   )

//   checkEdit(
//     "closure-block",
//     """|object A{
//        |  val a = {
//        |    val b = List(1,2,3)
//        |    val c = <<b.map(_ + 1)>>
//        |  }
//        | 
//        |}""".stripMargin,
//     1,
//     """|object A{
//        |  def newMethod(b: List[Int]): List[Int] = b.map(_ + 1)
//        |  val a = {
//        |    val b = List(1,2,3)
//        |    val c = newMethod(b)
//        |  }
//        | 
//        |}""".stripMargin,
//   )

//   checkEdit(
//     "tuple".tag(IgnoreScalaVersion("3.1.3", "3.2.0")),
//     """|object A{
//        |  def method(i: Int) = i + 1
//        |  val (t1, t2) = {
//        |    val b = 4
//        |    123 + <<method(b)>>
//        |  }
//        |}""".stripMargin,
//     1,
//     """|object A{
//        |  def method(i: Int) = i + 1
//        |  def newMethod(b: Int): Int = method(b)
//        |  val (t1, t2) = {
//        |    val b = 4
//        |    123 + newMethod(b)
//        |  }
//        |}""".stripMargin,
//   )

  def checkEdit(
      name: TestOptions,
      original: String,
      defLine: (Int, Int),
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {

      val edits = getAutoImplement(original, defLine)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      defLine: (Int, Int),
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val targetRegex = "<<(.+)>>".r
    val target = targetRegex.findAllMatchIn(original).toList match {
      case Nil => fail("Missing <<target>>")
      case t :: Nil => t.group(1)
      case _ => fail("Multiple <<targets>> found")
    }
    val code2 = original.replace("<<", "").replace(">>", "")
    val lines = original.split("\n")
    val firstLine = lines.indexWhere(_.contains("<<"))
    val lastLine = lines.indexWhere(_.contains(">>"))
    val firstChar = lines(firstLine).indexOf("<<")
    val lastChar = lines(lastLine).indexOf(">>")
    val range = new l.Range(new l.Position(firstLine, firstChar), new l.Position(lastLine, lastChar))
    val defnpos = new l.Range(new l.Position(defLine._1, 0), new l.Position(defLine._2, 0))
    pprint.pprintln(range)
    pprint.pprintln(defnpos)

    val result = presentationCompiler
      .extractMethod(
        CompilerRangeParams(URI.create(filename), code2, original.indexOf("<<"), original.indexOf(">>") - 2, cancelToken),
        range,
        new l.Range(new l.Position(defLine._1, 0), new l.Position(defLine._2, 0)),
      )
      .get()
    result.asScala.toList
  }

}
