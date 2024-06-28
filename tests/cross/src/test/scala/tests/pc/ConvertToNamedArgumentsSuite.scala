package tests.pc

import java.net.URI
import java.util.concurrent.ExecutionException

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.pc.CodeActionErrorMessages
import scala.meta.pc.DisplayableException

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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
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
       |}""".stripMargin
  )

  checkEdit(
    "tuple",
    """|class C {
       |  def f(t: (String, Int)) = 1
       |  val g = <<f(("bar" -> 1))>>
       |}
       |""".stripMargin,
    List(0),
    """|class C {
       |  def f(t: (String, Int)) = 1
       |  val g = f(t = ("bar" -> 1))
       |}
       |""".stripMargin
  )

  checkError(
    "java-object",
    """|object A{
       |  val a = <<new java.util.Vector(3)>>
       |}
       |""".stripMargin,
    List(0, 1),
    CodeActionErrorMessages.ConvertToNamedArguments.IsJavaObject
  )

  checkEdit(
    "block",
    """|class C {
       |  def f(o: Int) = 1
       |  val g = <<f({2})>>
       |}
       |""".stripMargin,
    List(0),
    """|class C {
       |  def f(o: Int) = 1
       |  val g = f(o = {2})
       |}
       |""".stripMargin
  )

  checkEdit(
    "multi-bracket",
    """|class C {
       |  def f(o: Int) = 1
       |  val g = <<f (({2}))>>
       |}
       |""".stripMargin,
    List(0),
    """|class C {
       |  def f(o: Int) = 1
       |  val g = f (o = ({2}))
       |}
       |""".stripMargin
  )

  checkEdit(
    "extends",
    """|abstract class AAA(a: Int)
       |
       |class B extends <<AAA(2)>>
       |""".stripMargin,
    List(0),
    """|abstract class AAA(a: Int)
       |
       |class B extends AAA(a = 2)
       |""".stripMargin
  )

  checkEdit(
    "extends-ugly-formatting",
    """|abstract class A(a: Int)
       |
       |class B
       |  extends <<A (2)>>
       |""".stripMargin,
    List(0),
    """|abstract class A(a: Int)
       |
       |class B
       |  extends A (a = 2)
       |""".stripMargin
  )

  checkEdit(
    "extends1".tag(IgnoreScala2),
    """|abstract class A(a: Int)
       |trait C(c: Int)
       |
       |class B extends <<A(2)>> with C(4)
       |""".stripMargin,
    List(0),
    """|abstract class A(a: Int)
       |trait C(c: Int)
       |
       |class B extends A(a = 2) with C(4)
       |""".stripMargin
  )

  checkEdit(
    "extends2".tag(IgnoreScala2),
    """|abstract class A(a: Int)
       |trait C(c: Int)
       |
       |class B extends A(2) with <<C(4)>>
       |""".stripMargin,
    List(0),
    """|abstract class A(a: Int)
       |trait C(c: Int)
       |
       |class B extends A(2) with C(c = 4)
       |""".stripMargin
  )

  def checkError(
      name: TestOptions,
      original: String,
      argIndices: List[Int],
      expectedErrorMsg: String
  ): Unit = {
    test(name) {
      try {
        val edits = convertToNamedArgs(original, argIndices)
        val (code, _, _) = params(original)
        val obtained = TextEdits.applyEdits(code, edits)
        fail(s"No error. Result: \n $obtained")
      } catch {
        case e: ExecutionException =>
          e.getCause() match {
            case cause: DisplayableException =>
              assertNoDiff(cause.getMessage(), expectedErrorMsg)
          }
      }
    }
  }

  def checkEdit(
      name: TestOptions,
      original: String,
      argIndices: List[Int],
      expected: String,
      compat: Map[String, String] = Map.empty
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
      filename: String = "file:/A.scala"
  ): List[l.TextEdit] = {
    val (code, _, offset) = params(original)
    val result = presentationCompiler
      .convertToNamedArguments(
        CompilerOffsetParams(URI.create(filename), code, offset, cancelToken),
        argIndices.map(new Integer(_)).asJava
      )
      .get()
    result.asScala.toList
  }

}
