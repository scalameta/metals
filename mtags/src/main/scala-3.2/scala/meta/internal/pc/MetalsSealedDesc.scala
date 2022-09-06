package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.Symbol

object MetalsSealedDesc:
  def sealedStrictDescendants(sym: Symbol)(using Context): List[Symbol] =
    sym.sealedStrictDescendants.filterNot(child =>
      child.is(Sealed) && (child.is(Abstract) || child.is(Trait))
    )
