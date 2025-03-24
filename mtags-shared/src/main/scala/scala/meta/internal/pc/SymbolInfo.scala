package scala.meta.internal.pc

object SymbolInfo {
  type IsClass = Option[Boolean]
  def getPartsFromSymbol(symbol: String): SymbolParts = {
    val index = symbol.lastIndexOf("/")
    val pkgString = symbol.take(index + 1)

    def loop(
        symbol: String,
        acc: List[(String, IsClass)]
    ): List[(String, IsClass)] =
      if (symbol.isEmpty()) acc.reverse
      else {
        val newSymbol = symbol.takeWhile(c => c != '.' && c != '#')
        val rest = symbol.drop(newSymbol.size)
        loop(
          rest.drop(1),
          (newSymbol, Some(rest.headOption.exists(_ == '#'))) :: acc
        )
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

  def getPartsFromFQCN(fqcn: String): SymbolParts = {
    val parts = fqcn.split('.').toList
    val pkgParts = parts.takeWhile(_.charAt(0).isLower).mkString("/")
    val names = parts.dropWhile(_.charAt(0).isLower).toList.map((_, None))
    SymbolParts(pkgParts, names, None)
  }

  case class SymbolParts(
      packagePart: String,
      names: List[(String, IsClass)],
      paramName: Option[String]
  )
}
