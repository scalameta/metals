package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.pc.DefinitionResult

import org.eclipse.lsp4j.Location

case class DefinitionResultImpl(
    symbol: String,
    locations: ju.List[Location],
    override val isResolved: Boolean = true,
    override val parameterNames: ju.List[String] = ju.Collections.emptyList(),
    override val parameterTypeNames: ju.List[String] =
      ju.Collections.emptyList()
) extends DefinitionResult

object DefinitionResultImpl {
  def empty: DefinitionResult = DefinitionResult.empty()
}
