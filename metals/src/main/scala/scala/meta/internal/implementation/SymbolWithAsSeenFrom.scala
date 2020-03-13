package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.SymbolInformation

final case class SymbolWithAsSeenFrom(
    symbolInformation: SymbolInformation,
    asSeenFrom: Map[String, String]
)
