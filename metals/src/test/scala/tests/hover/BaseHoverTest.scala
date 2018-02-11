package tests.hover

import scala.meta.metals.Uri
import org.langmeta.lsp.Hover
import scala.meta.metals.providers.HoverProvider
import scala.{meta => m}
import tests.search.BaseIndexTest
import io.circe.syntax._

abstract class BaseHoverTest extends BaseIndexTest {

  def check(
      filename: String,
      code: String,
      f: Hover => Unit
  ): Unit = {
    targeted(
      filename,
      code, { point =>
        val uri = Uri.file(filename)
        val input = m.Input.VirtualFile(uri.value, point.contents)
        val pos = m.Position.Range(input, point.offset, point.offset)
        indexInput(uri, point.contents)
        val result =
          HoverProvider.hover(symbols, uri, pos.startLine, pos.startColumn)
        f(result)
      }
    )

  }

  def check(
      filename: String,
      code: String,
      expectedValue: String
  ): Unit = {
    check(
      filename,
      code, { result =>
        val expected =
          s"""{
             |  "contents" : [
             |    {
             |      "language" : "scala",
             |      "value" : "$expectedValue"
             |    }
             |  ]
             |}""".stripMargin
        assertNoDiff(result.asJson, expected)
      }
    )
  }

  def checkMissing(
      filename: String,
      code: String
  ): Unit = {
    check(
      filename,
      code, { result =>
        assert(result.contents.isEmpty)
      }
    )
  }
}
