package scala.meta.internal.pc

import java.{util => ju}
import org.eclipse.lsp4j.Location
import scala.meta.pc.DefinitionResult

case class DefinitionResultImpl(
    symbol: String,
    locations: ju.List[Location]
) extends DefinitionResult

object DefinitionResultImpl {
  def empty: DefinitionResult =
    DefinitionResultImpl("", ju.Collections.emptyList())
}
