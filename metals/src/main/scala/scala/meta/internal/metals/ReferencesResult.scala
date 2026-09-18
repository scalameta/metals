package scala.meta.internal.metals

import org.eclipse.lsp4j.Location

case class ReferencesResult(
    symbol: String,
    locations: Seq[Location],
    isIncomplete: Boolean = false,
    processedCandidates: Int = 0,
    totalCandidates: Int = 0,
)

object ReferencesResult {
  def empty: ReferencesResult = ReferencesResult("", Nil)
}

case class ImplementationsResult[T](
    results: List[T],
    isIncomplete: Boolean = false,
    processedCandidates: Int = 0,
    totalCandidates: Int = 0,
)

object ImplementationsResult {
  def empty[T]: ImplementationsResult[T] = ImplementationsResult(Nil)
}
