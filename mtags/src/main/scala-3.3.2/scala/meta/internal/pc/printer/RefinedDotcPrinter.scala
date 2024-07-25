package scala.meta.internal.pc.printer

import scala.meta.internal.mtags.KeywordWrapper

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.printing.Texts.Text

/* In 3.4.x some changes were made to printer,
    but haven't managed to port all of them yet to the LTS */
abstract class RefinedDotcPrinter(_ctx: Context) extends RefinedPrinter(_ctx):

  def toTextPrefix(tp: Type) =
    tp match
      case tp: NamedType => super.toTextPrefixOf(tp)
      case tp => Text()

  override def nameString(name: Name): String =
    val nameStr = super.nameString(name)
    KeywordWrapper.Scala3.backtickWrap(nameStr)
  
  override def toText(tp: Type): Text =
    tp match
      case tp: TermRef
          if !tp.denotationIsCurrent && !homogenizedView ||
            tp.symbol.is(Module) || tp.symbol.name == nme.IMPORT =>
        toTextPrefix(tp.prefix) ~ selectionString(tp) ~ ".type"
      case tp: TermRef =>
        toTextPrefix(tp.prefix) ~ selectionString(tp)
      case tr: TypeRef =>
        super.toText(tr)
      case _ => super.toText(tp)
end RefinedDotcPrinter
