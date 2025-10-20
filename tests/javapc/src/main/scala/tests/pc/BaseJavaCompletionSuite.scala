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

abstract class BaseJavaCompletionSuite extends BaseJavaPCSuite {
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      filename: String = "A.java",
      filterText: Option[String] = None,
      filterItem: CompletionItem => Boolean = _ => true,
      editedFile: String = "",
  )(implicit loc: Location): Unit = {
    test(name) {
      val items = getItems(original, filename).filter(filterItem)
      val filtered = filterText match {
        case None => items
        case Some(text) =>
          items.filter(item => item.getLabel().contains(text))
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

      if (editedFile.nonEmpty) {
        assert(clue(filtered).length == 1, "Expected single completion item")
        val item = filtered.head
        val (code, _) = params(original)
        val obtained = TextEdits.applyEdits(code, item)
        assertNoDiff(
          obtained,
          editedFile,
        )
      }
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
