package tests.clients

import java.net.URLEncoder

import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.ServerCommands

import org.eclipse.{lsp4j => l}
import tests.BaseSuite

class ScalaVersionsSuite extends BaseSuite {

  val line = 0
  val character = 0
  val uri = "file:///home/alice/Main.scala"
  val pos = new l.Position(line, character)
  val location = new l.Location(uri, new l.Range(pos, pos))
  val symbol = "a/Main."

  val locationJson: String =
    s"""[{"uri":"$uri","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]"""
  val symbolJson: String = s"""["$symbol"]"""

  test("sublime") {
    val format = CommandHTMLFormat.Sublime
    assertNoDiff(
      ServerCommands.GotoPosition.toCommandLink(location, format),
      s"""subl:lsp_metals_goto_position {"parameters": $locationJson}"""
        .replaceAll("\"", "&#x22;")
    )
    assertNoDiff(
      ServerCommands.GotoSymbol.toCommandLink(symbol, format),
      s"""subl:lsp_metals_goto {"parameters": $symbolJson}"""
        .replaceAll("\"", "&#x22;")
    )
  }

  test("vscode") {
    val format = CommandHTMLFormat.VSCode
    val expectedParamsLocation = URLEncoder.encode(locationJson)
    val expectedParamsSymbol = URLEncoder.encode(symbolJson)
    assertNoDiff(
      ServerCommands.GotoPosition.toCommandLink(location, format),
      s"command:metals.goto-position?$expectedParamsLocation"
    )
    assertNoDiff(
      ServerCommands.GotoSymbol.toCommandLink(symbol, format),
      s"command:metals.goto?$expectedParamsSymbol"
    )
  }
}
