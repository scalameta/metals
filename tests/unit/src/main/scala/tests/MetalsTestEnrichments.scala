package tests

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}
import scala.{meta => m}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._

/**
 *  Equivalent to scala.meta.internal.metals.MetalsEnrichments
 *  but only for tests
 */
object MetalsTestEnrichments {
  implicit class XtensionTestLspRange(range: l.Range) {
    def formatMessage(
        severity: String,
        message: String,
        input: m.Input
    ): String = {
      try {
        val start = range.getStart
        val end = range.getEnd
        val pos = m.Position.Range(
          input,
          start.getLine,
          start.getCharacter,
          end.getLine,
          end.getCharacter
        )
        pos.formatMessage(severity, message)
      } catch {
        case e: IllegalArgumentException =>
          val result =
            s"${range.getStart.getLine}:${range.getStart.getCharacter} ${message}"
          scribe.error(result, e)
          result
      }
    }

  }
  implicit class XtensionTestDiagnostic(diag: l.Diagnostic) {
    def formatMessage(input: m.Input): String = {
      diag.getRange.formatMessage(
        diag.getSeverity.toString.toLowerCase(),
        diag.getMessage,
        input
      )
    }
  }
  implicit class XtensionMetaToken(token: m.Token) {
    def isIdentifier: Boolean = token match {
      case _: m.Token.Ident | _: m.Token.Interpolation.Id => true
      case _ => false
    }
    def toPositionParams(
        identifier: TextDocumentIdentifier
    ): TextDocumentPositionParams = {
      val range = token.pos.toLSP
      val start = range.getStart
      new TextDocumentPositionParams(identifier, start)
    }

  }

  implicit class XtensionDocumentSymbolOccurrence(info: l.SymbolInformation) {
    def toSymbolOccurrence: s.SymbolOccurrence = {
      val startRange = info.getLocation.getRange.getStart
      val endRange = info.getLocation.getRange.getEnd
      s.SymbolOccurrence(
        range = Some(
          new s.Range(
            startRange.getLine,
            startRange.getCharacter,
            startRange.getLine,
            startRange.getCharacter
          )
        ),
        // include end line for testing purposes
        symbol =
          s"${info.getContainerName}${info.getName}(${info.getKind}):${endRange.getLine + 1}",
        role = s.SymbolOccurrence.Role.DEFINITION
      )
    }
  }

}
