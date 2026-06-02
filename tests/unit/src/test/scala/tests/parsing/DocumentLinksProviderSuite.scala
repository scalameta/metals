package tests.parsing

import java.nio.file.Paths

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.DocumentLinksProvider
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j
import org.eclipse.lsp4j.DocumentLink
import tests.BaseSuite

class DocumentLinksProviderSuite extends BaseSuite {
  import DocumentLinksProviderSuite._
  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))

  test("find all links and report correct ranges") {
    val buffers = new Buffers()
    val path = workspace.resolve("Baz.scala")
    val content =
      """|package foo.bar
         |
         |import baz.quux
         |import zzyx.xxx //Here's a link in comment: https://example.com some more text here
         |
         |http://example.com?wat=This%20is%20a%20compilation%20error%20but%20who%20cares
         |
         |/** Here's a link in Javadoc
         |  * @see https://scala-lang.org
         |  */
         |class Baz {}
         |""".stripMargin
    buffers.put(path, content)
    val documentLinksProvider = new DocumentLinksProvider(buffers)
    val obtained = documentLinksProvider.getLinks(path)
    val expected = List(
      documentLink(3, 44, 63, "https://example.com", "https://example.com"),
      documentLink(5, 0, 78,
        "http://example.com?wat=This%20is%20a%20compilation%20error%20but%20who%20cares",
        "http://example.com?wat=This is a compilation error but who cares"),
      documentLink(8, 9, 31, "https://scala-lang.org", "https://scala-lang.org"),
    )
    assertEquals(obtained.asScala.toList, expected)
  }

}

object DocumentLinksProviderSuite {
  def documentLink(
      line: Int,
      start: Int,
      end: Int,
      target: String,
      tooltip: String,
  ): DocumentLink = {
    val result = new DocumentLink()
    result.setRange(
      new lsp4j.Range(
        new lsp4j.Position(line, start),
        new lsp4j.Position(line, end),
      )
    )
    result.setTarget(target)
    result.setTooltip(tooltip)
    result
  }
}
