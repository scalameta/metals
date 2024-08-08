package scala.meta.internal.pc.printer

import scala.meta.internal.mtags.KeywordWrapper

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.core.Names.Name

abstract class RefinedDotcPrinter(_ctx: Context) extends RefinedPrinter(_ctx):
  override def nameString(name: Name): String =
    val nameStr = super.nameString(name)
    KeywordWrapper.Scala3Keywords.backtickWrap(nameStr)
