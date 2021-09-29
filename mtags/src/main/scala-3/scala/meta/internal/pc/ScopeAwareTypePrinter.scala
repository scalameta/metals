package scala.meta.internal.pc

import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.printing.Texts.*

class ScopeAwareTypePrinter(indexed: IndexedContext)
    extends RefinedPrinter(indexed.ctx):

  private val defaultWidth = 1000

  def typeString(tpw: Type): String =
    toText(tpw).mkString(defaultWidth, false)

  override def toTextPrefix(tp: Type): Text = controlled {
    tp match
      case tp: ThisType =>
        tp.tref match
          case tpe @ TypeRef(NoPrefix, designator) =>
            val sym =
              if designator.isInstanceOf[Symbol] then
                designator.asInstanceOf[Symbol]
              else tpe.termSymbol

            val text = super.toTextPrefix(tp)
            if sym.is(ModuleClass) && indexed.toplevelClashes(sym) then
              Str("_root_.") ~ text
            else text
          case _ => super.toTextPrefix(tp)
      case _ => super.toTextPrefix(tp)
  }
end ScopeAwareTypePrinter
