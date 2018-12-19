package tests

import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}

/**
 *  Equivalent to scala.meta.internal.metals.MetalsEnrichments
 *  but only for tests
 */
object MetalsTestEnrichments {

  implicit class XtensionDocumentSymbolOccurrence(
      documentSymbol: l.SymbolInformation
  ) {
    def toSymbolOccurrence: s.SymbolOccurrence = {
      val startRange = documentSymbol.getLocation.getRange.getStart
      val endRange = documentSymbol.getLocation.getRange.getEnd
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
          s"${documentSymbol.getName}(${documentSymbol.getKind}):${endRange.getLine + 1}",
        role = s.SymbolOccurrence.Role.DEFINITION
      )
    }
  }

}
