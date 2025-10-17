package scala.meta.internal.metals

import scala.meta.internal.metals.WorkspaceSymbolQuery.AlternativeQuery

/**
 * A query for workspace/symbol.
 *
 * @param query the user query itself.
 * @param alternatives all query alternatives for this query. For non-lowercase queries
 *                     this list has always length 1 but for all-lowercase queries we
 *                     have combinations of query with guesses for which characters in the query
 *                     should be capitalized.
 */
case class WorkspaceSymbolQuery(
    query: String,
    alternatives: Array[AlternativeQuery],
    isTrailingDot: Boolean,
    isClasspath: Boolean = true,
    isShortQueryRetry: Boolean = false
) {
  val isShortQuery: Boolean = query.length < Fuzzy.PrefixSearchLimit
  def matches(bloom: StringBloomFilter): Boolean =
    alternatives.exists(_.matches(bloom))

  def matches(symbol: CharSequence): Boolean =
    if (isShortQuery && !isShortQueryRetry) Fuzzy.prefixMatch(query, symbol)
    else alternatives.exists(_.matches(symbol, isTrailingDot))
}

object WorkspaceSymbolQuery {
  def exactDescriptorPart(
      query: String
  ): WorkspaceSymbolQuery = {
    WorkspaceSymbolQuery(
      query,
      Array(AlternativeQuery.exactDescriptorPart(query)),
      isTrailingDot = false
    )
  }
  def exact(
      query: String,
      isShortQueryRetry: Boolean = false
  ): WorkspaceSymbolQuery = {
    WorkspaceSymbolQuery(
      query,
      Array(AlternativeQuery(query, isShortQueryRetry)),
      isTrailingDot = false
    )
  }
  def fuzzy(query: String): WorkspaceSymbolQuery = {
    val isTrailingDot = query.endsWith(".")
    val isClasspath = query.contains(";")
    val actualQuery = query.stripSuffix(".").replace(";", "")
    WorkspaceSymbolQuery(
      actualQuery,
      AlternativeQuery.all(actualQuery),
      isTrailingDot,
      isClasspath
    )
  }

  case class AlternativeQuery(
      query: String,
      bloomFilterQueries: Array[CharSequence],
      bloomFilterCachedQueries: Array[Long]
  ) {
    def matches(bloom: StringBloomFilter): Boolean =
      bloomFilterCachedQueries.forall(bloom.mightContain)
    def matches(symbol: CharSequence, isTrailingDot: Boolean): Boolean =
      Fuzzy.matches(query, symbol, if (isTrailingDot) 1 else 0)

  }

  object AlternativeQuery {
    def exactDescriptorPart(query: String): AlternativeQuery = {
      val hasher = StringBloomFilter.forEstimatedSize(0)
      val fingerprint = FingerprintedCharSequence.exactWord(query)
      AlternativeQuery(
        query,
        Array(fingerprint),
        Array(hasher.computeHashCode(fingerprint))
      )
    }
    def apply(
        query: String,
        isShortQueryRetry: Boolean = false
    ): AlternativeQuery = {
      val hasher = StringBloomFilter.forEstimatedSize(0)
      val queries = Fuzzy
        .bloomFilterQueryStrings(query, isShortQueryRetry = isShortQueryRetry)
        .toArray
      val bytes = queries.map { query => hasher.computeHashCode(query) }
      AlternativeQuery(query, queries, bytes)
    }
    def all(query: String): Array[AlternativeQuery] = {
      val isAllLowercase = query.forall(_.isLower)
      if (isAllLowercase) {
        // We special handle lowercase queries by guessing alternative capitalized queries.
        // Benchmark in akka/akka show that we pay a manageable performance overhead from this:
        // - "actorref" with guessed capitalization responds in 270ms.
        // - "ActorRef" with 0 guesses responds in 190ms.
        val buf = Array.newBuilder[AlternativeQuery]
        // First, test the exact lowercase query.
        buf += AlternativeQuery(query)
        // Second, uppercase all characters, this makes "fsmp" match "FiniteStateMachineProvider".
        buf += AlternativeQuery(query.toUpperCase)
        // Third, uppercase only the first character, this makes "files" match "Files".
        buf += AlternativeQuery(query.capitalize)
        // Fourth, uppercase the first character and up to two other characters, this makes "actorref" match "ActorRef"
        // and also "wosypro" match "WorkspaceSymbolProvider".
        buf ++= TrigramSubstrings
          .uppercased(query)
          .map(AlternativeQuery(_))
        buf.result()
      } else {
        Array(AlternativeQuery(query))
      }
    }
  }

}
