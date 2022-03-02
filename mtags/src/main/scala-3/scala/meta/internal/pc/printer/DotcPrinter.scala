package scala.meta.internal.pc.printer

import scala.meta.internal.pc.IndexedContext
import scala.meta.internal.pc.printer.DotcPrinter.ForInferredType

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.printing.Texts.Text

/**
 * A limited subset of function that we use from compiler's printer
 */
trait DotcPrinter:
  def name(sym: Symbol): String
  def fullName(sym: Symbol): String
  def keywords(sym: Symbol): String
  def tpe(t: Type): String

object DotcPrinter:

  private val defaultWidth = 1000

  class Std(using ctx: Context) extends RefinedPrinter(ctx) with DotcPrinter:

    override def nameString(name: Name): String =
      super.nameString(name.stripModuleClassSuffix)

    def name(sym: Symbol): String =
      nameString(sym)

    def keywords(sym: Symbol): String =
      keyString(sym)

    def tpe(t: Type): String =
      toText(t).mkString(defaultWidth, false)

    def fullName(sym: Symbol): String =
      fullNameString(sym)
  end Std

  /**
   * This one is used only for adding inferred type
   * The difference with std is that in case of name clashe it prepends `_root_`
   */
  class ForInferredType(indexed: IndexedContext) extends Std(using indexed.ctx):
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
  end ForInferredType
end DotcPrinter
