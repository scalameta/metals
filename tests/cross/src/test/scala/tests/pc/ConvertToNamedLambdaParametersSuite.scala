package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class ConvertToNamedLambdaParametersSuite extends BaseCodeActionSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  checkEdit(
    "Int => Int function in map",
    """|object A{
       |  val a = List(1, 2).map(<<_>> + 1)
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).map(i => i + 1)
       |}""".stripMargin
  )

  checkEdit(
    "Int => Int function in map with another wildcard lambda",
    """|object A{
       |  val a = List(1, 2).map(<<_>> + 1).map(_ + 1)
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).map(i => i + 1).map(_ + 1)
       |}""".stripMargin
  )

  checkEdit(
    "String => String function in map",
    """|object A{
       |  val a = List("a", "b").map(<<_>> + "c")
       |}""".stripMargin,
    """|object A{
       |  val a = List("a", "b").map(s => s + "c")
       |}""".stripMargin
  )

  checkEdit(
    "Person => Person function to custom method",
    """|object A{
       |  case class Person(name: String, age: Int)
       |  val bob = Person("Bob", 30)
       |  def m[A](f: Person => A): A = f(bob)
       |  m(_<<.>>name)
       |}
       |""".stripMargin,
    """|object A{
       |  case class Person(name: String, age: Int)
       |  val bob = Person("Bob", 30)
       |  def m[A](f: Person => A): A = f(bob)
       |  m(p => p.name)
       |}
       |""".stripMargin
  )

  checkEdit(
    "(String, Int) => Int function in map with multiple underscores",
    """|object A{
       |  val a = List(("a", 1), ("b", 2)).map(<<_>> + _)
       |}""".stripMargin,
    """|object A{
       |  val a = List(("a", 1), ("b", 2)).map((s, i) => s + i)
       |}""".stripMargin
  )

  checkEdit(
    "Int => Int function in map with multiple underscores",
    """|object A{
       |  val a = List(1, 2).map(x => x -> (x + 1)).map(<<_>> + _)
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).map(x => x -> (x + 1)).map((i, i1) => i + i1)
       |}""".stripMargin
  )

  checkEdit(
    "Int => Float function in nested lambda 1",
    """|object A{
       |  val a = List(1, 2).flatMap(List(_).flatMap(v => List(v, v + 1).map(<<_>>.toFloat)))
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).flatMap(List(_).flatMap(v => List(v, v + 1).map(i => i.toFloat)))
       |}""".stripMargin
  )

  checkEdit(
    "Int => Float function in nested lambda 1",
    """|object A{
       |  val a = List(1, 2).flatMap(List(<<_>>).flatMap(v => List(v, v + 1).map(_.toFloat)))
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).flatMap(i => List(i).flatMap(v => List(v, v + 1).map(_.toFloat)))
       |}""".stripMargin
  )

  checkEdit(
    "Int => Float function in nested lambda with shadowing",
    """|object A{
       |  val a = List(1, 2).flatMap(List(<<_>>).flatMap(i => List(i, i + 1).map(_.toFloat)))
       |}""".stripMargin,
    """|object A{
       |  val a = List(1, 2).flatMap(i1 => List(i1).flatMap(i => List(i, i + 1).map(_.toFloat)))
       |}""".stripMargin
  )

  checkEdit(
    "(String, String, String, String, String, String, String) => String function in map",
    """|object A{
       |  val a = List(
       |    ("a", "b", "c", "d", "e", "f", "g"),
       |    ("h", "i", "j", "k", "l", "m", "n")
       |  ).map(_<< >>+ _ + _ + _ + _ + _ + _)
       |}""".stripMargin,
    """|object A{
       |  val a = List(
       |    ("a", "b", "c", "d", "e", "f", "g"),
       |    ("h", "i", "j", "k", "l", "m", "n")
       |  ).map((s, s1, s2, s3, s4, s5, s6) => s + s1 + s2 + s3 + s4 + s5 + s6)
       |}""".stripMargin
  )

  checkEdit(
    "Long => Long with match and wildcard pattern",
    """|object A{
       |  val a = List(1L, 2L).map(_ match {
       |    case 1L => 1L
       |    case _ => <<2L>>
       |  })
       |}""".stripMargin,
    """|object A{
       |  val a = List(1L, 2L).map(l => l match {
       |    case 1L => 1L
       |    case _ => 2L
       |  })
       |}""".stripMargin
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit location: Location): Unit =
    test(name) {
      val edits = convertToNamedLambdaParameters(original)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def convertToNamedLambdaParameters(
      original: String,
      filename: String = "file:/A.scala"
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .convertToNamedLambdaParameters(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken)
      )
      .get()
    result.asScala.toList
  }
}
