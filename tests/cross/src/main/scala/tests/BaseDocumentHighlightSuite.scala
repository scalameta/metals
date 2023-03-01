package tests

import java.net.URI

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.DocumentHighlight

class BaseDocumentHighlightSuite extends BasePCSuite with RangeReplace {

  def check(
      name: TestOptions,
      original: String,
      //   compat: Map[String, String] = Map.empty,
  )(implicit location: Location): Unit =
    test(name) {

      val edit = original.replaceAll("(<<|>>)", "")
      val expected = original.replaceAll("@@", "")
      val base = original.replaceAll("(<<|>>|@@)", "")

      val (code, offset) = params(edit)
      val highlights = presentationCompiler
        .documentHighlight(
          CompilerOffsetParams(
            URI.create("file:/Highlight.scala"),
            code,
            offset,
            EmptyCancelToken,
          )
        )
        .get()
        .asScala
        .toList
        .sortWith(compareHighlights)
        .reverse

      assertEquals(
        renderHighlightsAsString(base, highlights),
        expected,
      )

    }
  private def compareHighlights(
      h1: DocumentHighlight,
      h2: DocumentHighlight,
  ) = {
    val r1 = h1.getRange().getStart()
    val r2 = h2.getRange().getStart()
    r1.getLine() < r2.getLine() || (r1.getLine() == r2.getLine() && r1
      .getCharacter() < r2.getCharacter())
  }
}
