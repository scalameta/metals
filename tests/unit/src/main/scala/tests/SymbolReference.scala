package tests

import org.eclipse.{lsp4j => l}
import scala.{meta => m}
import scala.meta.internal.metals.PositionSyntax._

case class SymbolReference(
    symbol: String,
    location: l.Location,
    pos: m.Position
) {
  def format: String = pos.formatMessage(symbol, "")
  override def toString: String = format
}
