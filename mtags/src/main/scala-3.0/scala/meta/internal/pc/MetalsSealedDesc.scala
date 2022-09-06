package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.Symbol

object MetalsSealedDesc:
  // For scala 3.0.0 and 3.0.1 method `sealedStrictDescendants` is not available
  def sealedStrictDescendants(sym: Symbol)(using Context): List[Symbol] =
    Nil
