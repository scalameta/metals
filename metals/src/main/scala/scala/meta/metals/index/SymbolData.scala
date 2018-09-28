package scala.meta.metals.index

case class SymbolData(
    symbol: String,
    definition: Option[Position] = None,
    references: Map[String, Ranges] = Map.empty,
    flags: Long = 0L,
    name: String = "",
    signature: String = ""
)

case class Position(
    uri: String,
    range: Option[Range]
)

case class Ranges(ranges: Seq[Range] = Nil) {
  def addRanges(range: Range*): Ranges =
    copy(ranges = range ++ ranges)
}

case class Range(
    startLine: Int,
    startColumn: Int,
    endLine: Int,
    endColumn: Int
)
