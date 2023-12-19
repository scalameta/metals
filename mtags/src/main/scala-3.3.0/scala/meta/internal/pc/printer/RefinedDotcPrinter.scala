package scala.meta.internal.pc.printer

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.printing.RefinedPrinter

abstract class RefinedDotcPrinter(_ctx: Context) extends RefinedPrinter(_ctx)
