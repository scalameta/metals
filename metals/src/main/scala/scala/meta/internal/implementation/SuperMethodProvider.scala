package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.SymbolInformation

object SuperMethodProvider {

  def findSuperForMethodOrField(
      methodSymbolInformation: SymbolInformation
  ): Option[String] =
    methodSymbolInformation.overriddenSymbols.headOption.filter(isNonStopSymbol)

  def getSuperMethodHierarchy(
      methodSymbolInformation: SymbolInformation
  ): List[String] = {
    methodSymbolInformation.overriddenSymbols.filter { sym =>
      isNonStopSymbol(sym)
    }.toList
  }

  private def isNonStopSymbol(symbol: String): Boolean =
    !stopSymbols.exists(stop => symbol.startsWith(stop))

  private final val stopSymbols: Set[String] = Set(
    "scala/AnyRef#", "scala/Serializable#", "java/io/Serializable#",
    "java/lang/Object#", "scala/AnyVal#", "scala/Any#"
  )
}
