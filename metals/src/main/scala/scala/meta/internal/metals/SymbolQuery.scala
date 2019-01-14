package scala.meta.internal.metals

import com.google.common.hash.BloomFilter

case class SymbolQuery(
    query: String,
    combinations: Array[SymbolQueryCombination]
) {
  def matches(bloom: BloomFilter[CharSequence]): Boolean =
    combinations.exists(_.matches(bloom))
  def matches(symbol: CharSequence): Boolean =
    combinations.exists(_.matches(symbol))
}
object SymbolQuery {
  def fromTextQuery(query: String): SymbolQuery = {
    SymbolQuery(
      query,
      SymbolQueryCombination.all(query)
    )
  }
}
