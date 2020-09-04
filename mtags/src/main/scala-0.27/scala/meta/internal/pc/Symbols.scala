package scala.meta.internal.pc

import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.transform.SymUtils._

object Symbols {
  def isDeprecated(symbol: Symbol)(using ctx: Context) = symbol.isDeprecated
}
