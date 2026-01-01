package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.TextEdits
import scala.meta.pc.CancelToken

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CompletionItem

class BaseJavaCompletionSuite extends BaseJavaPCSuite {
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      filename: String = "A.java",
      filterText: Option[String] = None,
  )(implicit loc: Location): Unit = {
    test(name) {
      val items = getItems(original, filename)
      val filtered = filterText match {
        case None => items
        case Some(text) => items.filter(_.getLabel().contains(text))
      }
      val out = new StringBuilder()

      filtered.foreach { item =>
        out.append(item.getLabel)
        out.append("\n")
      }

      assertNoDiff(
        out.toString(),
        expected,
      )
    }
  }

  def checkEdit(
      name: TestOptions,
      original: String,
      expected: String,
      filterText: String = "",
      assertSingleItem: Boolean = true,
      filter: String => Boolean = _ => true,
      command: Option[String] = None,
      itemIndex: Int = 0,
      filename: String = "A.java",
      filterItem: CompletionItem => Boolean = _ => true,
  )(implicit loc: Location): Unit = {
    test(name) {
      val items =
        getItems(original, filename)
          .filter(item => filter(item.getLabel) && filterItem(item))
      if (items.isEmpty) fail("obtained empty completions!")
      if (assertSingleItem && items.length != 1) {
        fail(
          s"expected single completion item, obtained ${items.length} items.\n${items}"
        )
      }
      if (items.size <= itemIndex) fail("Not enough completion items")
      val item = items(itemIndex)
      val (code, _) = params(original, filename)
      val obtained = TextEdits.applyEdits(code, item)
      assertNoDiff(
        obtained,
        expected,
      )
      if (filterText.nonEmpty) {
        assertNoDiff(item.getFilterText, filterText, "Invalid filter text")
      }
      assertNoDiff(
        Option(item.getCommand).fold("")(_.getCommand),
        command.getOrElse(""),
        "Invalid command",
      )
    }
  }

  private def cancelToken: CancelToken = EmptyCancelToken

  private def getItems(
      original: String,
      filename: String,
  ): Seq[CompletionItem] = {
    val (code, offset) = params(original)
    val offsetParams = CompilerOffsetParams(
      Paths.get(filename).toUri,
      code,
      offset,
      cancelToken,
    )

    presentationCompiler.complete(offsetParams).get().getItems.asScala.toList
  }
}
