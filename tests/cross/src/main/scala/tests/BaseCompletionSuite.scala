package tests

import org.eclipse.lsp4j.CompletionItem
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

  def getItems(original: String): Seq[CompletionItem] = {
    val (code, offset) = params(original)
    val result = resolvedCompletions(
      CompilerOffsetParams("A.scala", code, offset, cancelToken)
    )
    result.getItems.asScala.sortBy(_.getSortText)
  }

  def checkEdit(
      name: String,
      original: String,
      expected: String,
      filterText: String = ""
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val items = getItems(original)
      if (items.length != 1) {
        fail(
          s"expected single completion item, obtained ${items.length} items.\n${items}"
        )
      }
      val item = items.head
      val (code, _) = params(original)
      val obtained = TextEdits.applyEdit(code, item.getTextEdit)
      assertNoDiff(obtained, expected)
      if (filterText.nonEmpty) {
        assertNoDiff(item.getFilterText, filterText, "Invalid filter text")
      }
    }
  }

  def checkSnippet(
      name: String,
      original: String,
      expected: String
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val items = getItems(original)
      val obtained = items
        .map(item => Option(item.getInsertText).getOrElse(item.getLabel))
        .mkString("\n")
      assertNoDiff(obtained, expected)
    }
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
      postAssert: () => Unit = () => (),
      topLines: Option[Int] = None
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val out = new StringBuilder()
      val baseItems = getItems(original)
      val items = topLines match {
        case Some(top) => baseItems.take(top)
        case None => baseItems
      }
      items.foreach { item =>
        val label = TestCompletions.getFullyQualifiedLabel(item)
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
