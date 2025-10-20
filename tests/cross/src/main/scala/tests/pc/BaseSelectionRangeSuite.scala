package tests.pc

import java.nio.file.Paths
import java.{util => ju}

import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.pc.OffsetParams

import munit.TestOptions
import org.eclipse.{lsp4j => l}
import tests.Assertions
import tests.BasePCSuite

abstract class BaseSelectionRangeSuite extends BasePCSuite {

  def check(
      name: TestOptions,
      original: String,
      expectedRanges: List[String],
      compat: Map[String, List[String]] = Map.empty
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      val (code, offset) = params(original, "SelectionRange.scala")
      val offsetParams: ju.List[OffsetParams] = List[OffsetParams](
        CompilerOffsetParams(
          Paths.get("SelectionRange.scala").toUri(),
          code,
          offset,
          EmptyCancelToken
        )
      ).asJava

      val selectionRanges = presentationCompiler
        .selectionRange(offsetParams)
        .get()
        .asScala
        .toList

      // Note that this takes some liberty since the spec isn't the clearest on
      // the way this is done and different LSP clients seems to impliment this
      // differently. Mainly, we mimic what VS Code does and what other servers
      // like jdtls do and we send in a single position. Once that positions is
      // recieved we check the Selection range and all the parents in that range
      // to ensure it contains the positions we'd expect it to contain. In the
      // case of VS Code, they never make a second request, and instead they
      // just rely on the tree to continue selecting. So we mimic that here in
      // the tests.
      assertSelectionRanges(
        selectionRanges.headOption,
        compatOrDefault(expectedRanges, compat, scalaVersion),
        code
      )
    }
  }

  private def assertSelectionRanges(
      range: Option[l.SelectionRange],
      expected: List[String],
      original: String
  )(implicit loc: munit.Location): Unit = {
    assert(range.nonEmpty)
    expected.headOption.foreach { expectedRange =>
      val withRange = applyRanges(original, range.get)
      Assertions.assertNoDiff(withRange, expectedRange)
      assertSelectionRanges(range.map(_.getParent()), expected.tail, original)
    }
  }

  private def applyRanges(
      text: String,
      selectionRange: l.SelectionRange
  ): String = {
    val input = Input.String(text)

    val pos = selectionRange
      .getRange()
      .toMeta(input)
      .getOrElse(
        throw new RuntimeException(
          s"Range ${selectionRange.getRange()} not contained in:\n$text"
        )
      )
    val out = new java.lang.StringBuilder()
    out.append(text, 0, pos.start)
    out.append(">>region>>")
    out.append(text, pos.start, pos.end)
    out.append("<<region<<")
    out.append(text, pos.end, text.length)
    out.toString
  }
}
