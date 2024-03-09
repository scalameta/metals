package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols.Symbol

object MetalsSealedDesc:
  def sealedDescendants(sym: Symbol)(using Context): List[Symbol] =
    sym.sealedDescendants.filter(child =>
      !(child.is(Sealed) && (child.is(Abstract) || child.is(Trait)))
        && child.maybeOwner.exists
        && (child.isPublic || child.isAccessibleFrom(sym.info))
        && child.name != tpnme.LOCAL_CHILD
    )
