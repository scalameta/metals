package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.pc.DefinitionResult

import org.eclipse.lsp4j.Location

case class DefinitionResultImpl(
    symbol: String,
    locations: ju.List[Location]
) extends DefinitionResult

object DefinitionResultImpl {
  def empty: DefinitionResult =
    DefinitionResultImpl("", ju.Collections.emptyList())
}
