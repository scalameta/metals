package tests.pc

import java.net.URI

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class ExtractMethodSuite extends BaseCodeActionSuite {

  checkEdit(
    "simple-expr",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  @@val a = <<123 + method(b)>>
        |}""".stripMargin,
    s"""|object A{
        |  val b = 4
        |  def method(i: Int) = i + 1
        |  def newMethod(): Int =
        |    123 + method(b)
        |
        |  val a = newMethod()
        |}""".stripMargin,
  )

  checkEdit(
    "no-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(b, 10)>>
        |  }
        |
        |}""".stripMargin,
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
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(): Int =
                         |    val b = 2
                         |    123 + method(b, 10)
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod()
                         |  }
                         |
                         |}""".stripMargin),
  )

  checkEdit(
    "single-param",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val c = 1
        |    <<val b = 2
        |    123 + method(c, 10)>>
        |  }
        |}""".stripMargin,
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
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(c: Int): Int =
                         |    val b = 2
                         |    123 + method(c, 10)
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod(c)
                         |  }
                         |}""".stripMargin),
  )

  checkEdit(
    "name-gen",
    s"""|object A{
        |  def newMethod() = 1
        |  def newMethod0(a: Int) = a + 1
        |  def method(i: Int) = i + i
        |  @@val a = <<method(5)>>
        |}""".stripMargin,
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

  checkEdit(
    "multi-param",
    s"""|object A{
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  @@val a = { 
        |    val c = 5
        |    val b = 4
        |    <<123 + method(c, b) + method(b,c)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  val c = 3
        |  def method(i: Int, j: Int) = i + 1
        |  def newMethod(b: Int, c: Int): Int =
        |    123 + method(c, b) + method(b,c)
        |
        |  val a = { 
        |    val c = 5
        |    val b = 4
        |    newMethod(b, c)
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "higher-scope",
    s"""|object A{
        |  val b = 4
        |  def method(i: Int, j: Int, k: Int) = i + j + k
        |  val a = {
        |    @@def f() = {
        |      val c = 1
        |      <<val d = 3
        |      method(d, b, c)>>
        |    }
        |  }
        |}""".stripMargin,
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
    Map(">=3.0.0" -> s"""|object A{
                         |  val b = 4
                         |  def method(i: Int, j: Int, k: Int) = i + j + k
                         |  val a = {
                         |    def newMethod(c: Int): Int =
                         |      val d = 3
                         |      method(d, b, c)
                         |
                         |    def f() = {
                         |      val c = 1
                         |      newMethod(c)
                         |    }
                         |  }
                         |}""".stripMargin),
  )

  checkEdit(
    "match",
    s"""|object A {
        |  @@val a = {
        |    val b = 4
        |    <<b + 2 match {
        |      case _ => b
        |    }>>
        |  }
        |}""".stripMargin,
    s"""|object A {
        |  def newMethod(b: Int): Int =
        |    b + 2 match {
        |      case _ => b
        |    }
        |
        |  val a = {
        |    val b = 4
        |    newMethod(b)
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "nested-declarations",
    s"""|object A {
        |  @@val a = {
        |    val c = 1
        |    <<val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2>>
        |  }
        |}""".stripMargin,
    s"""|object A {
        |  def newMethod(c: Int): Int = {
        |    val b = {
        |      val c = 2
        |      c + 1
        |    }
        |    c + 2
        |  }
        |  val a = {
        |    val c = 1
        |    newMethod(c)
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A {
                         |  def newMethod(c: Int): Int =
                         |    val b = {
                         |      val c = 2
                         |      c + 1
                         |    }
                         |    c + 2
                         |
                         |  val a = {
                         |    val c = 1
                         |    newMethod(c)
                         |  }
                         |}""".stripMargin),
  )

  checkEdit(
    "class-param",
    s"""|object A{
        |  @@def f1() = {
        |    class B(b: Int) {
        |      def f2() = <<b + 2>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def newMethod(b: Int): Int =
        |    b + 2
        |
        |  def f1() = {
        |    class B(b: Int) {
        |      def f2() = newMethod(b)
        |    }
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "method-param",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1(a: Int) = {
        |    <<method(a)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(a: Int): Int =
        |    method(a)
        |
        |  def f1(a: Int) = {
        |    newMethod(a)
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "method-type",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1[T](a: T) = {
        |    <<a>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod[T](a: T): T =
        |    a
        |
        |  def f1[T](a: T) = {
        |    newMethod(a)
        |  }
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
      val code = original
        .replace("<<", "")
        .replace(">>", "")
        .replace("@@", "")
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val code2 = original
      .replace("<<", "")
      .replace(">>", "")
      .replace("@@", "")
    val lines = original.split("\n")
    val metaRange = Position.Range(
      Input.String(code2),
      original
        .replace("@@", "")
        .indexOf("<<"),
      original
        .replace("<<", "")
        .replace("@@", "")
        .indexOf(">>"),
    )
    val range = new l.Range(
      new l.Position(metaRange.startLine, metaRange.startColumn),
      new l.Position(metaRange.endLine, metaRange.endColumn),
    )
    val extractionPosLine = lines.indexWhere(_.contains("@@"))
    val extractionPosChar =
      lines(extractionPosLine).replace("<<", "").replace(">>", "").indexOf("@@")
    val extractionPos = new l.Position(extractionPosLine, extractionPosChar)
    val result = presentationCompiler
      .extractMethod(
        CompilerOffsetParams(
          URI.create(filename),
          code2,
          metaRange.end,
          cancelToken,
        ),
        range,
        extractionPos,
      )
      .get()
    result.asScala.toList
  }

}
