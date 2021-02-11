package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite
import tests.BuildInfoVersions

class InsertInferredTypeSuite extends BaseCodeActionSuite {

  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

  checkEdit(
    "val",
    """|object A{
       |  val <<alpha>> = 123
       |}""".stripMargin,
    """|object A{
       |  val alpha: Int = 123
       |}""".stripMargin
  )

  checkEdit(
    "tuple",
    """|object A{
       |  val (<<alpha>>, beta) = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  val (alpha: Int, beta) = (123, 12)
       |}""".stripMargin
  )

  checkEdit(
    "var",
    """|object A{
       |  var <<alpha>> = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  var alpha: (Int, Int) = (123, 12)
       |}""".stripMargin
  )

  checkEdit(
    "def",
    """|object A{
       |  def <<alpha>> = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha: (Int, Int) = (123, 12)
       |}""".stripMargin
  )

  checkEdit(
    "def-comment",
    """|object A{
       |  def <<alpha>> /* [] */= (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha: (Int, Int) /* [] */= (123, 12)
       |}
       |""".stripMargin
  )

  checkEdit(
    "def-comment-param",
    """|object A{
       |  def <<alpha>>() /* [] */= (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha(): (Int, Int) /* [] */= (123, 12)
       |}
       |""".stripMargin
  )

  checkEdit(
    "def-param",
    """|object A{
       |  def <<alpha>>(a : String) = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha(a : String): (Int, Int) = (123, 12)
       |}""".stripMargin
  )

  checkEdit(
    "def-type-param",
    """|object A{
       |  def <<alpha>>[T] = (123, 12)
       |}""".stripMargin,
    """|object A{
       |  def alpha[T]: (Int, Int) = (123, 12)
       |}""".stripMargin
  )

  checkEdit(
    "auto-import",
    """|object A{
       |  val <<buffer>> = List("").toBuffer
       |}""".stripMargin,
    """|import scala.collection.mutable.Buffer
       |object A{
       |  val buffer: Buffer[String] = List("").toBuffer
       |}""".stripMargin
  )

  checkEdit(
    "lambda",
    """|object A{
       |  val toStringList = List(1, 2, 3).map(<<int>> => int.toString)
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map((int: Int) => int.toString)
       |}""".stripMargin
  )

  checkEdit(
    "lambda-existing-brace",
    """|object A{
       |  val toStringList = List(1, 2, 3).map( /*{}*/(<<int>>) => int.toString)
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map( /*{}*/(int: Int) => int.toString)
       |}""".stripMargin
  )

  checkEdit(
    "lambda-brace",
    """|object A{
       |  val toStringList = List(1, 2, 3).map{<<int>> => int.toString}
       |}""".stripMargin,
    """|object A{
       |  val toStringList = List(1, 2, 3).map{int: Int => int.toString}
       |}""".stripMargin
  )

  checkEdit(
    "pattern-match-paren",
    """|object A{
       |  val list = List(1, 2, 3) match {
       |    case <<head>> :: tail => tail
       |    case Nil => Nil
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = List(1, 2, 3) match {
       |    case (head: Int) :: tail => tail
       |    case Nil => Nil
       |  }
       |}""".stripMargin
  )

  checkEdit(
    "pattern-match-tuple",
    """|object A{
       |  val list = (1, 2) match {
       |    case (3, two) => 3
       |    case (one, <<two>>) => 2
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = (1, 2) match {
       |    case (3, two) => 3
       |    case (one, two: Int) => 2
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "pattern-match-option",
    """|object A{
       |  Option(1) match {
       |    case Some(<<t>>) => t
       |    case None =>
       |  }
       |}""".stripMargin,
    """|object A{
       |  Option(1) match {
       |    case Some(t: Int) => t
       |    case None =>
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "pattern-match-list",
    """|object A{
       |  List(1, 2, 3, 4) match {
       |    case List(<<t>>, next, other, _) => t
       |    case _ =>
       |  }
       |}""".stripMargin,
    """|object A{
       |  List(1, 2, 3, 4) match {
       |    case List(t: Int, next, other, _) => t
       |    case _ =>
       |  }
       |}
       |""".stripMargin
  )

  checkEdit(
    "pattern-match",
    """|object A{
       |  val list = 1 match {
       |    case 2 => "Two!"
       |    case <<otherDigit>> => "Not two!"
       |  }
       |}""".stripMargin,
    """|object A{
       |  val list = 1 match {
       |    case 2 => "Two!"
       |    case otherDigit: Int => "Not two!"
       |  }
       |}""".stripMargin
  )

  checkEdit(
    "for-comprehension",
    """|object A{
       |  for {
       |    <<i>> <- 1 to 10
       |    j <- 1 to 11
       |  } yield (i, j)
       |}""".stripMargin,
    """|object A{
       |  for {
       |    i: Int <- 1 to 10
       |    j <- 1 to 11
       |  } yield (i, j)
       |}
       |""".stripMargin
  )

  checkEdit(
    "for-comprehension",
    """|object A{
       |  for {
       |    i <- 1 to 10
       |    <<j>> = i
       |  } yield (i, j)
       |}""".stripMargin,
    """|object A{
       |  for {
       |    i <- 1 to 10
       |    j: Int = i
       |  } yield (i, j)
       |}
       |""".stripMargin
  )
  checkEdit(
    "path",
    """|import java.nio.file.Paths
       |object ExplicitResultTypesPrefix {
       |  class Path
       |  def path = Paths.get("")
       |  object inner {
       |    val file = path
       |    object inner {
       |      val nio: java.nio.file.Path = path
       |      object inner {
       |        val <<java>> = path
       |      }
       |    }
       |  }
       |
       |}""".stripMargin,
    """|import java.nio.file.Paths
       |object ExplicitResultTypesPrefix {
       |  class Path
       |  def path = Paths.get("")
       |  object inner {
       |    val file = path
       |    object inner {
       |      val nio: java.nio.file.Path = path
       |      object inner {
       |        val java: _root_.java.nio.file.Path = path
       |      }
       |    }
       |  }
       |
       |}
       |""".stripMargin
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String
  ): Unit =
    test(name) {
      val edits = getAutoImplement(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  def getAutoImplement(
      original: String,
      filename: String = "A.scala"
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .insertInferredType(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }

}
