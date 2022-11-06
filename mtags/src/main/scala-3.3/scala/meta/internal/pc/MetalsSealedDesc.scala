package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.Symbol

object MetalsSealedDesc:
  def sealedStrictDescendants(sym: Symbol)(using Context): List[Symbol] =
    sym.sealedStrictDescendants.filter(child =>
      !(child.is(Sealed) && (child.is(Abstract) || child.is(Trait)))
        && (child.isPublic || child.isAccessibleFrom(sym.info)) &&
        child.name != tpnme.LOCAL_CHILD
    )
