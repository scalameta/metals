package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
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
        |  @@class B(val b: Int) {
        |    def f2 = <<b + 2>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def newMethod(b: Int): Int =
        |    b + 2
        |
        |  class B(val b: Int) {
        |    def f2 = newMethod(b)
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
  checkEdit(
    "method-type-no-param",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1[T](a: T) = {
        |    <<Set.empty[T]>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod[T](): Set[T] =
        |    Set.empty[T]
        |
        |  def f1[T](a: T) = {
        |    newMethod()
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "inner-conflict",
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  @@val a = {
        |    val d = 3
        |    <<val b = {
        |      val d = 4
        |      d + 1
        |    }
        |    123 + method(b, 10)>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int, j: Int) = i + j
        |  def newMethod(): Int = {
        |    val b = {
        |      val d = 4
        |      d + 1
        |    }
        |    123 + method(b, 10)
        |  }
        |  val a = {
        |    val d = 3
        |    newMethod()
        |  }
        |}""".stripMargin,
    Map(">=3.0.0" -> s"""|object A{
                         |  def method(i: Int, j: Int) = i + j
                         |  def newMethod(): Int =
                         |    val b = {
                         |      val d = 4
                         |      d + 1
                         |    }
                         |    123 + method(b, 10)
                         |
                         |  val a = {
                         |    val d = 3
                         |    newMethod()
                         |  }
                         |}""".stripMargin),
  )
  // Currently we are not supporting extracting inner methods
  // Issue: https://github.com/scalameta/metals/issues/4360
  checkEdit(
    "extract-def",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@def f1(a: Int) = {
        |    def m2(b: Int) = b + 1
        |    <<method(2 + m2(a))>>
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(a: Int): Int =
        |    method(2 + m2(a))
        |
        |  def f1(a: Int) = {
        |    def m2(b: Int) = b + 1
        |    newMethod(a)
        |  }
        |}""".stripMargin,
  )

  checkEdit(
    "extract-class",
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  @@class Car(val color: Int) {
        |    def add(other: Car): Car = {
        |      <<new Car(other.color + color)>>
        |    }
        |  }
        |}""".stripMargin,
    s"""|object A{
        |  def method(i: Int) = i + 1
        |  def newMethod(color: Int, other: Car): Car =
        |    new Car(other.color + color)
        |
        |  class Car(val color: Int) {
        |    def add(other: Car): Car = {
        |      newMethod(color, other)
        |    }
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
      val (edits, code) = getAutoImplement(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def getAutoImplement(
      original: String,
      filename: String = "file:/A.scala",
  ): (List[l.TextEdit], String) = {
    val withoutExtractionPos = original.replace("@@", "")
    val onlyRangeClose = withoutExtractionPos
      .replace("<<", "")
    val code = onlyRangeClose.replace(">>", "")
    val extractionPos = CompilerOffsetParams(
      URI.create(filename),
      code,
      original.indexOf("@@"),
      cancelToken,
    )
    val rangeParams = CompilerRangeParams(
      URI.create(filename),
      code,
      withoutExtractionPos.indexOf("<<"),
      onlyRangeClose.indexOf(">>"),
      cancelToken,
    )
    val result = presentationCompiler
      .extractMethod(
        rangeParams,
        extractionPos,
      )
      .get()
    (result.asScala.toList, code)
  }

}
