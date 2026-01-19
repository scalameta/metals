package tests.pc

import tests.BaseLspSuite
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.Position

import org.eclipse.lsp4j.SignatureHelpParams

class Issue7911LspSuite extends BaseLspSuite("issue-7911") {
  test("signature-help-scala3") {
    val filename = "Main.scala"
    val code = """|case class TxInInfo()
                  |case class TxOut()
                  |
                  |extension [A](l: List[A])
                  |  def map2[B](f: A => B): List[B] = ???
                  |
                  |val x = List(TxInInfo())
                  |val y = x.map2[TxOut](@@)
                  |""".stripMargin
    val (baseText, offset) = {
      val idx = code.indexOf("@@")
      if (idx < 0) throw new RuntimeException("No @@ found")
      (code.replace("@@", ""), idx)
    }

    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "3.3.3"
            |/src/main/scala/$filename
            |$baseText
            |""".stripMargin
      )
      _ <- server.didOpen(s"src/main/scala/$filename")
      input = scala.meta.Input.VirtualFile(filename, baseText)
      pos = scala.meta.Position.Range(input, offset, offset)
      params = new SignatureHelpParams(
        new TextDocumentIdentifier(
          server.toPath(s"src/main/scala/$filename").toURI.toString
        ),
        new Position(pos.startLine, pos.startColumn),
      )
      service = server.server
        .asInstanceOf[org.eclipse.lsp4j.services.TextDocumentService]
      signatures <- service.signatureHelp(params).asScala
    } yield {
      val label =
        if (signatures.getSignatures.isEmpty) "EMPTY"
        else signatures.getSignatures.get(0).getLabel
      assertNoDiff(
        label,
        "map2[TxInInfo][TxOut](f: TxInInfo => TxOut): List[TxOut]",
      )
    }
  }
}
