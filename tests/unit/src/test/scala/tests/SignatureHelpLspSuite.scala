package tests

import tests.BaseLspSuite
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.Position

class SignatureHelpLspSuite extends BaseLspSuite("signature-help") {
  test("active-parameter-negative-scala3") {
    val rawCode =
      """package a
        |object A {
        |  def foo(x: Int): Unit = ???
        |  foo(x = Int@@)
        |}
        |""".stripMargin
    val cursor = rawCode.indexOf("@@")
    val code = rawCode.replace("@@", "")

    val lines = code.take(cursor).split("\n", -1)
    val line = lines.length - 1
    val char = lines.last.length

    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "scalaVersion": "${BuildInfo.scala3}"
            |  }
            |}
            |/a/src/main/scala/a/A.scala
            |$code
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      path = workspace.resolve("a/src/main/scala/a/A.scala")
      params = new TextDocumentPositionParams(
        new TextDocumentIdentifier(path.toURI.toString),
        new Position(line, char),
      )
      help <- server.signatureHelp(params)
    } yield {
      if (help.getActiveParameter != null) {
        assert(
          help.getActiveParameter >= 0,
          s"Active parameter should be >= 0 but was ${help.getActiveParameter}",
        )
      }
    }
  }
}
