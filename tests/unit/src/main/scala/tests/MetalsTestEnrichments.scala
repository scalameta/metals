package tests

import org.eclipse.{lsp4j => l}
import scala.meta.internal.{semanticdb => s}

/**
 *  Equivalent to scala.meta.internal.metals.MetalsEnrichments
 *  but only for tests
 */
object MetalsTestEnrichments {

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
