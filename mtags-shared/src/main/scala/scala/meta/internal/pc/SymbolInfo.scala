package scala.meta.internal.pc

object SymbolInfo {
  type IsClass = Boolean
  def getPartsFromSymbol(symbol: String): SymbolParts = {
    val index = symbol.lastIndexOf("/")
    val pkgString = symbol.take(index + 1)

    def loop(
        symbol: String,
        acc: List[(String, Boolean)]
    ): List[(String, Boolean)] =
      if (symbol.isEmpty()) acc.reverse
      else {
        val newSymbol = symbol.takeWhile(c => c != '.' && c != '#')
        val rest = symbol.drop(newSymbol.size)
        loop(rest.drop(1), (newSymbol, rest.headOption.exists(_ == '#')) :: acc)
      }

    val (toNames, rest) = {
      val withoutPackage = symbol.drop(index + 1)
      val i = withoutPackage.indexOf('(')
      if (i < 0) (withoutPackage, "")
      else withoutPackage.splitAt(i)
    }
    val paramName =
      Some(rest.dropWhile(_ != '.').drop(2).dropRight(1)).filter(_.nonEmpty)
    val names = loop(toNames, List.empty)

    SymbolParts(pkgString, names, paramName)
  }

  case class SymbolParts(
      packagePart: String,
      names: List[(String, IsClass)],
      paramName: Option[String]
  )
}
