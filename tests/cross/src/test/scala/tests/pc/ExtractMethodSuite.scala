package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class ExtractMethodSuite extends BaseCodeActionSuite {

  checkEdit(
    "single-param".tag(IgnoreScala3),
    s"""|  object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  val a = {
        |    <<123 + method(b)>>
        |  }
        |}""".stripMargin,
    List(3, 2, 5, 2),
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  def newMethod(): Int =
        |    123 + method(b)
        |
        |  val a = {
        |    newMethod()
        |  }
        |}""".stripMargin,
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      defLine: List[Int],
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {

      val edits = getAutoImplement(original, defLine)
      val code = original.replace("<<", "").replace(">>", "")
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      defLine: List[Int],
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val code2 = original.replace("<<", "").replace(">>", "")
    val lines = original.split("\n")
    val firstLine = lines.indexWhere(_.contains("<<"))
    val lastLine = lines.indexWhere(_.contains(">>"))
    val firstChar = lines(firstLine).indexOf("<<")
    val lastChar =
      if (firstLine == lastLine) lines(lastLine).indexOf(">>") - 2
      else lines(lastLine).indexOf(">>")
    val range = new l.Range(
      new l.Position(firstLine, firstChar),
      new l.Position(lastLine, lastChar),
    )
    val result = presentationCompiler
      .extractMethod(
        CompilerRangeParams(
          URI.create(filename),
          code2,
          original.indexOf("<<"),
          original.indexOf(">>") - 2,
          cancelToken,
        ),
        range,
        new l.Range(
          new l.Position(defLine(0), defLine(1)),
          new l.Position(defLine(2), defLine(3)),
        ),
      )
      .get()
    result.asScala.toList
  }

}
