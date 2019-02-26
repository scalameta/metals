package tests

import scala.collection.JavaConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CompletionItems
import scala.meta.internal.metals.PCEnrichments._
import scala.meta.pc.CancelToken

abstract class BaseCompletionSuite extends BasePCSuite {

  def cancelToken: CancelToken = EmptyCancelToken

  private def resolvedCompletions(
      params: CompilerOffsetParams
  ): CompletionItems = {
    val result = pc.complete(params)
    val newItems = result.getItems.asScala.map { item =>
      val symbol = item.data.get.symbol
      pc.completionItemResolve(item, symbol, cancelToken)
    }
    result.setItems(newItems.asJava)
    result
  }

  def check(
      name: String,
      original: String,
      expected: String,
      includeDocs: Boolean = false,
      includeCommitCharacter: Boolean = false,
      compat: Map[String, String] = Map.empty,
      postProcessObtained: String => String = identity,
      stableOrder: Boolean = true,
      postAssert: () => Unit = () => ()
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val (code, offset) = params(original)
      val result = resolvedCompletions(
        CompilerOffsetParams("A.scala", code, offset, cancelToken)
      )
      val out = new StringBuilder()
      val items = result.getItems.asScala.sortBy(_.getSortText)
      items.foreach { item =>
        val label =
          if (item.getInsertText == null) item.getLabel else item.getInsertText
        val commitCharacter =
          if (includeCommitCharacter)
            item.getCommitCharacters.asScala.mkString(" (commit: '", " ", "')")
          else ""
        val documentation = doc(item.getDocumentation)
        if (includeDocs && documentation.nonEmpty) {
          out.append("> ").append(documentation).append("\n")
        }
        out
          .append(label)
          .append(item.getDetail)
          .append(commitCharacter)
          .append("\n")
      }
      assertNoDiff(
        sortLines(
          stableOrder,
          postProcessObtained(trimTrailingSpace(out.toString()))
        ),
        sortLines(stableOrder, getExpected(expected, compat))
      )
      postAssert()
    }
  }

  def trimTrailingSpace(string: String): String = {
    string.linesIterator
      .map(_.replaceFirst("\\s++$", ""))
      .mkString("\n")
  }

}
