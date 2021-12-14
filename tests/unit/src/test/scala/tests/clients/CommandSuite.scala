package tests.clients

import tests.BaseSuite
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.CommandHTMLFormat
import org.eclipse.{lsp4j => l}
import java.net.URLEncoder

class ScalaVersionsSuite extends BaseSuite {

  val line = 0
  val character = 0
  val uri = "file:///home/alice/Main.scala"
  val pos = new l.Position(line, character)
  val location = new l.Location(uri, new l.Range(pos, pos))
  val symbol = "a/Main."

  val expectedParamsLocation = URLEncoder.encode(
    s"""[{"uri":"$uri","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]"""
  )
  val expectedParamsSymbol = URLEncoder.encode(
    s"""["$symbol"]"""
  )

  test("sublime") {
    val format = CommandHTMLFormat.Sublime
    assertNoDiff(
      ServerCommands.GotoPosition.toCommandLink(location, format),
      s"href='subl:goto-position {$expectedParamsLocation}'"
    )
    assertNoDiff(
      ServerCommands.GotoSymbol.toCommandLink(symbol, format),
      s"href='subl:goto {$expectedParamsSymbol}'"
    )
  }

  test("vscode") {
    val format = CommandHTMLFormat.VSCode
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
