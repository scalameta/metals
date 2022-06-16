package scala.meta.internal.pc.printer

import scala.meta.internal.pc.IndexedContext
import scala.meta.internal.pc.printer.DotcPrinter.ForInferredType
import scala.meta.internal.pc.printer.ShortenedNames.PrettyType

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

    override def toText(tp: Type): Text =
      // Override the behavior for `AppliedType` because
      // `toText` in RefinedPrinter doesn't pretty print AppliedType
      // if tycon is instance of PrettyType.
      // For example, if we don't override `toText`,
      // CompletionoverrideConfigSuite's `package` test will fail with
      // completing `def function: Int <none> String = ${0:???}`
      // instead of `def function: f.Function[Int, String] = ${0:???}`
      tp match
        case tp: AppliedType =>
          tp.tycon match
            case p: PrettyType =>
              Str(p.toString) ~ "[" ~ toText(tp.args, ", ") ~ "]"
            case other => super.toText(tp)
        case other => super.toText(tp)
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
