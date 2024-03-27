package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.pc.ReferencesResult

import org.eclipse.lsp4j.Location

case class ReferencesResultImpl(
    symbol: String,
    locations: ju.List[Location]
) extends ReferencesResult

object ReferencesResultImpl {
  def empty: ReferencesResult =
    ReferencesResultImpl("", ju.Collections.emptyList())
}
