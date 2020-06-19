package scala.meta.internal.metals

import scala.meta.internal.semanticdb.SymbolOccurrence

/**
 * A symbol occurrence that appeared with the given edit distance */
case class ResolvedSymbolOccurrence(
    distance: TokenEditDistance,
    occurrence: Option[SymbolOccurrence]
)
