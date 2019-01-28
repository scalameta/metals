package tests

import scala.collection.JavaConverters._
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.CompletionItems
import scala.meta.internal.metals.PCEnrichments._

abstract class BaseCompletionSuite extends BasePCSuite {

  def checkLength(
      name: String,
      original: String,
      expected: Int,
      compat: Map[String, Int] = Map.empty
  ): Unit = {
    test(name) {
      val (code, offset) = params(original)
      val result = pc.complete(CompilerOffsetParams("A.scala", code, offset))
      assertEquals(
        result.getItems.size(),
        getExpected(expected, compat)
      )
    }
  }

  private def resolvedCompletions(
      params: CompilerOffsetParams
  ): CompletionItems = {
    val result = pc.complete(params)
    val newItems = result.getItems.asScala.map { item =>
      val symbol = item.data.get.symbol
      pc.completionItemResolve(item, symbol)
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
      compat: Map[String, String] = Map.empty
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val (code, offset) = params(original)
      val result =
        resolvedCompletions(CompilerOffsetParams("A.scala", code, offset))
      val out = new StringBuilder()
      result.getItems.asScala.sortBy(_.getSortText).foreach { item =>
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
      assertNoDiff(out.toString(), getExpected(expected, compat))
    }
  }

}
