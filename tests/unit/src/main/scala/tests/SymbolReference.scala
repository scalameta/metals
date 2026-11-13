package tests

import scala.meta as m

import scala.meta.internal.metals.PositionSyntax.*

import org.eclipse.lsp4j as l

case class SymbolReference(
    symbol: String,
    location: l.Location,
    pos: m.Position,
) {
  def format: String = pos.formatMessage(symbol, "").stripLineEnd
  override def toString: String = format
}
