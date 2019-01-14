package scala.meta.internal.metals

import com.google.common.hash.BloomFilter

case class SymbolQueryCombination(
    query: String,
    combinations: Array[CharSequence]
) {
  def matches(bloom: BloomFilter[CharSequence]): Boolean =
    combinations.forall(bloom.mightContain)
  def matches(symbol: CharSequence): Boolean =
    Fuzzy.matches(query, symbol)
}

object SymbolQueryCombination {
  def apply(query: String): SymbolQueryCombination = {
    SymbolQueryCombination(
      query,
      Fuzzy.bloomFilterQueryStrings(query).toArray
    )
  }
  def all(query: String): Array[SymbolQueryCombination] = {
    val isAllLowercase = query.forall(_.isLower)
    if (isAllLowercase) {
      // We special handle lowercase queries by guessing alternative capitalized queries.
      // Benchmark in akka/akka show that we pay a manageable performance overhead from this:
      // - "actorref" with guessed capitalization responds in 270ms.
      // - "ActorRef" with 0 guesses responds in 190ms.
      val buf = Array.newBuilder[SymbolQueryCombination]
      // First, test the exact lowercase query.
      buf += SymbolQueryCombination(query)
      // Second, uppercase all characters, this makes "fsmp" match "FiniteStateMachineProvider".
      buf += SymbolQueryCombination(query.toUpperCase)
      // Third, uppercase only the first character, this makes "files" match "Files".
      buf += SymbolQueryCombination(query.capitalize)
      // Fourth, uppercase the first character and up to two other characters, this makes "actorref" match "ActorRef"
      // and also "wosypro" match "WorkspaceSymbolProvider".
      buf ++= TrigramSubstrings.uppercased(query).map(SymbolQueryCombination(_))
      buf.result()
    } else {
      Array(SymbolQueryCombination(query))
    }
  }
}
