package tests.pc

import java.net.URI
import java.util.concurrent.ExecutionException

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.CancelToken
import scala.meta.pc.DisplayableException

import munit.Location
import munit.TestOptions
import org.eclipse.{lsp4j => l}

trait BaseJavaExtractMethodSuite extends BaseJavaPCSuite {

  protected def cancelToken: CancelToken = EmptyCancelToken

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      filename: String = "A.java",
  )(implicit location: Location): Unit =
    test(name) {
      val (edits, code) = extractMethodEdits(original, filename)
      val obtained = TextEdits.applyEdits(code, edits)
      assertNoDiff(obtained, expected)
    }

  def checkError(
      name: TestOptions,
      original: String,
      expectedError: String,
      filename: String = "A.java",
  )(implicit location: Location): Unit =
    test(name) {
      try {
        extractMethodEdits(original, filename)
        fail("Expected DisplayableException but none was thrown")
      } catch {
        case e: ExecutionException =>
          e.getCause() match {
            case cause: DisplayableException =>
              assertNoDiff(cause.getMessage(), expectedError)
            case other =>
              fail(s"Expected DisplayableException but got: $other")
          }
      }
    }

  def extractMethodEdits(
      original: String,
      filename: String = "A.java",
  ): (List[l.TextEdit], String) = {
    val withoutExtractionPos = original.replace("@@", "")
    val onlyRangeClose = withoutExtractionPos.replace("<<", "")
    val code = onlyRangeClose.replace(">>", "")
    val extractionOffset = markerOffset(original, "@@")
    val rangeStart = markerOffset(withoutExtractionPos, "<<")
    val rangeEnd = markerOffset(onlyRangeClose, ">>")
    val extractionPos = CompilerOffsetParams(
      URI.create(s"file:///$filename"),
      code,
      extractionOffset,
      cancelToken,
    )
    val rangeParams = CompilerRangeParams(
      URI.create(s"file:///$filename"),
      code,
      rangeStart,
      rangeEnd,
      cancelToken,
    )
    val result =
      presentationCompiler.extractMethod(rangeParams, extractionPos).get()
    (result.asScala.toList, code)
  }

  private def markerOffset(text: String, marker: String): Int = {
    val index = text.indexOf(marker)
    if (index < 0) fail(s"missing $marker")
    text
      .take(index)
      .replace("@@", "")
      .replace("<<", "")
      .replace(">>", "")
      .length
  }
}
