package scala.meta.internal.pc

import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.printing.Texts._

class ScopeAwareTypePrinter(indexed: IndexedContext)
    extends RefinedPrinter(indexed.ctx) {

  private val defaultWidth = 1000

  def typeString(tpw: Type): String = {
    toText(tpw).mkString(defaultWidth, false)
  }

  override def toTextPrefix(tp: Type): Text = controlled {
    tp match {
      case tp: ThisType =>
        tp.tref match {
          case tpe @ TypeRef(NoPrefix, designator) =>
            val sym =
              if (designator.isInstanceOf[Symbol])
                designator.asInstanceOf[Symbol]
              else tpe.termSymbol

            val text = super.toTextPrefix(tp)
            if (sym.is(ModuleClass) && indexed.toplevelClashes(sym))
              Str("_root_.") ~ text
            else
              text
          case _ => super.toTextPrefix(tp)
        }
      case _ => super.toTextPrefix(tp)
    }
  }
}
