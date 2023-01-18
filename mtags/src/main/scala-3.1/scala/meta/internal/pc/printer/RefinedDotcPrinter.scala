package scala.meta.internal.pc.printer

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.printing.RefinedPrinter

abstract class RefinedDotcPrinter(_ctx: Context) extends RefinedPrinter(_ctx):

  override protected def toTextRefinement(rt: RefinedType) =
    val keyword = rt.refinedInfo match
      case _: ExprType | _: MethodOrPoly => "def "
      case _: TypeBounds => "type "
      case _: TypeProxy => "val "
      case _ => ""
    (keyword ~ refinementNameString(rt) ~ toTextRHS(rt.refinedInfo)).close
end RefinedDotcPrinter
