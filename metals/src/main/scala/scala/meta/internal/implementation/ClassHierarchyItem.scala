package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.SymbolInformation

final case class ClassHierarchyItem(
    symbolInformation: SymbolInformation,
    asSeenFrom: Map[String, String]
)
