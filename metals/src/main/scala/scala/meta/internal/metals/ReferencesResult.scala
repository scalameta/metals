package scala.meta.internal.metals

import org.eclipse.lsp4j.Location

case class ReferencesResult(symbol: String, locations: Seq[Location])

object ReferencesResult {
  def empty: ReferencesResult = ReferencesResult("", Nil)
}
