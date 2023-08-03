package tests.pc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.CancelToken

import munit.TestOptions
import org.eclipse.lsp4j.CompletionItem

class BaseJavaCompletionSuite extends BaseJavaPCSuite {
  def check(
      name: TestOptions,
      original: String,
      expected: String,
      filename: String = "A.java",
  ): Unit = {
    test(name) {
      val items = getItems(original, filename)
      val out = new StringBuilder()

      items.foreach { item =>
        out.append(item.getLabel)
        out.append("\n")
      }

      assertNoDiff(
        out.toString(),
        expected,
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
