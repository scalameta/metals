package scala.meta.internal.mtags

import scala.meta.internal.jsemanticdb.Semanticdb

object SemanticdbRanges {
  implicit val rangeOrdering: Ordering[Semanticdb.Range] =
    Ordering.by(r =>
      (
        r.getStartLine(),
        r.getStartCharacter(),
        r.getEndLine(),
        r.getEndCharacter()
      )
    )
  implicit val occurrenceOrdering: Ordering[Semanticdb.SymbolOccurrence] =
    Ordering.by(r => (r.getRange(), r.getSymbol(), r.getRole()))
  implicit class XtensionSemanticdbRange(r: Semanticdb.Range) {
    def containsPosition(line: Int, character: Int): Boolean = {
      r.getStartLine() <= line &&
      r.getEndLine() >= line &&
      r.getStartCharacter() <= character &&
      r.getEndCharacter() >= character
    }
    def overlapsRange(other: Semanticdb.Range): Boolean = {
      r.containsPosition(other.getStartLine(), other.getStartCharacter()) ||
      r.containsPosition(other.getEndLine(), other.getEndCharacter())
    }
  }

}
