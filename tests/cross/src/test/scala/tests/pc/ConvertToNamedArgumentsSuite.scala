package tests.pc

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.BaseCodeActionSuite

class ConvertToNamedArgumentsSuite extends BaseCodeActionSuite {

  override protected def requiresScalaLibrarySources: Boolean = true

  checkEdit(
    "scala-std-lib",
    """|object A{
       |  val a = <<scala.math.max(1, 2)>>
       |}""".stripMargin,
    List(0, 1),
    """|object A{
       |  val a = scala.math.max(x = 1, y = 2)
       |}""".stripMargin,
  )

  checkEdit(
    "backticked-name",
    """|object A{
       |  final case class Foo(`type`: Int, arg: String)
       |  val a = <<Foo(1, "a")>>
       |}""".stripMargin,
    List(0, 1),
    """|object A{
       |  final case class Foo(`type`: Int, arg: String)
       |  val a = Foo(`type` = 1, arg = "a")
       |}""".stripMargin,
  )

  checkEdit(
    "backticked-name-method",
    """|object A{
       |  def foo(`type`: Int, arg: String) = "a"
       |  val a = <<foo(1, "a")>>
       |}""".stripMargin,
    List(0, 1),
    """|object A{
       |  def foo(`type`: Int, arg: String) = "a"
       |  val a = foo(`type` = 1, arg = "a")
       |}""".stripMargin,
  )

  checkEdit(
    "new-apply",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)
       |  val a = <<new Foo(1, param2 = 2)>>
       |}""".stripMargin,
    List(0),
    """|object Something {
       |  class Foo(param1: Int, param2: Int)
       |  val a = new Foo(param1 = 1, param2 = 2)
       |}""".stripMargin,
  )
  checkEdit(
    "new-apply-multiple",
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = <<new Foo(1, param2 = 2)(3)>>
       |}""".stripMargin,
    List(0, 2),
    """|object Something {
       |  class Foo(param1: Int, param2: Int)(param3: Int)
       |  val a = new Foo(param1 = 1, param2 = 2)(param3 = 3)
       |}""".stripMargin,
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      argIndices: List[Int],
      expected: String,
      compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {
      val edits = convertToNamedArgs(original, argIndices)
      val (code, _, _) = params(original)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
    }

  def convertToNamedArgs(
      original: String,
      argIndices: List[Int],
      filename: String = "file:/A.scala",
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .convertToNamedArguments(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken),
        argIndices.map(new Integer(_)).asJava,
      )
      .get()
    result.asScala.toList
  }

}
