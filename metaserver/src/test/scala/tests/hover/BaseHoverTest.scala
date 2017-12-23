package tests.hover

import scala.meta.languageserver.Uri
import scala.meta.languageserver.providers.HoverProvider
import scala.{meta => m}
import langserver.messages.Hover
import play.api.libs.json.Json
import tests.search.BaseIndexTest

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
        val obtained = Json.prettyPrint(Json.toJson(result))
        val expected =
          s"""{
             |  "contents" : [ {
             |    "language" : "scala",
             |    "value" : "$expectedValue"
             |  } ]
             |}""".stripMargin
        assertNoDiff(obtained, expected)
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
