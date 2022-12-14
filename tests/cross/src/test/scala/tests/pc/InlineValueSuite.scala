package tests.pc

import tests.BaseCodeActionSuite
import munit.TestOptions
import munit.Location

import scala.meta.internal.metals.TextEdits
import org.eclipse.{lsp4j => l}
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerRangeParams
import java.net.URI
import scala.meta.internal.mtags.CommonMtagsEnrichments
import scala.meta.internal.pc.InlineValueProvider.{Errors => InlineErrors}

class InlineValueSuite extends BaseCodeActionSuite with CommonMtagsEnrichments {

  checkEdit(
    "inline-local",
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1
       |    val p: Int = <<o>> + 2
       |  } 
       |}""".stripMargin,
    inlineAll = false,
    """|object Main {
       |  def u(): Unit = {
       |    val p: Int = 1 + 2
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-all-local",
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1
       |    val p: Int = <<o>> + 2
       |    val i: Int = o + 3
       |  } 
       |}""".stripMargin,
    inlineAll = true,
    """|object Main {
       |  def u(): Unit = {
       |    val p: Int = 1 + 2
       |    val i: Int = 1 + 3
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-local-brackets",
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1 + 6
       |    val p: Int = 2 - <<o>>
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
    inlineAll = false,
    """|object Main {
       |  def u(): Unit = {
       |    val o: Int = 1 + 6
       |    val p: Int = 2 - (1 + 6)
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-all-local-brackets",
    """|object Main {
       |  def u(): Unit = {
       |    val h: Int = 9
       |    val <<o>>: Int = 1 + 6
       |    val p: Int = h - o
       |    val k: Int = o
       |  } 
       |}""".stripMargin,
    inlineAll = true,
    """|object Main {
       |  def u(): Unit = {
       |    val h: Int = 9
       |    val p: Int = h - (1 + 6)
       |    val k: Int = 1 + 6
       |  } 
       |}""".stripMargin,
  )

  checkEdit(
    "inline-not-local",
    """|object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - <<o>>
       |}""".stripMargin,
    inlineAll = false,
    """|object Main {
       |  val o: Int = 6
       |  val p: Int = 2 - 6
       |}""".stripMargin,
  )

  checkErorr(
    "inline-all-not-local",
    """|object Main {
       |  val <<o>>: Int = 6
       |  val p: Int = 2 - o
       |}""".stripMargin,
    inlineAll = true,
    InlineErrors.notLocal,
  )

  def checkEdit(
      name: TestOptions,
      original: String,
      inlineAll: Boolean,
      expected: String,
      compat: Map[String, String] = Map.empty,
      filename: String = "file:/A.scala",
  )(implicit location: Location): Unit =
    test(name) {
      getInlineEdits(original, inlineAll, filename) match {
        case Left(error) => fail(s"Error: $error")
        case Right(edits) => {
          val (code, _, _) = params(original)
          val obtained = TextEdits.applyEdits(code, edits)
          assertNoDiff(obtained, getExpected(expected, compat, scalaVersion))
        }
      }
    }

  def checkErorr(
      name: TestOptions,
      original: String,
      inlineAll: Boolean,
      expectedError: String,
      filename: String = "file:/A.scala",
  )(implicit location: Location): Unit =
    test(name) {
      getInlineEdits(original, inlineAll, filename) match {
        case Left(error) => assertNoDiff(error, expectedError)
        case Right(_) => fail("No error found")
      }
    }

  def getInlineEdits(
      original: String,
      inlineAll: Boolean,
      filename: String,
  ): Either[String, List[l.TextEdit]] = {
    val (code, _, offsetEnd) = params(original)
    val offset = original.indexOf("<<")
    val result = presentationCompiler
      .inlineValue(
        CompilerRangeParams(
          URI.create(filename),
          code,
          offset,
          offsetEnd,
          cancelToken,
        ),
        inlineAll,
      )
      .get()
    result.asScala.map(_.asScala.toList)
  }

}
