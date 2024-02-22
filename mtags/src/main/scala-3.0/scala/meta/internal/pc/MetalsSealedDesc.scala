package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.Symbol

object MetalsSealedDesc:
  // For scala 3.0.0 and 3.0.1 method `sealedDescendants` is not available
  def sealedDescendants(sym: Symbol)(using Context): List[Symbol] = sym
