package scala.meta.internal.pc

import dotty.tools.dotc.printing.RefinedPrinter
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Flags
import scala.language.implicitConversions

class SymbolPrinter(implicit ctx: Context) extends RefinedPrinter(ctx) {

  private val defaultWidth = 1000

  override def nameString(name: Name): String = {
    name.stripModuleClassSuffix.toString()
  }

  def typeString(tpw: Type): String = {
    toText(tpw).mkString(defaultWidth, false)
  }

  def fullDefinition(sym: Symbol, tpe: Type): String = {

    def isNullary =
      tpe match {
        case tpe: (MethodType | PolyType) =>
          false
        case other =>
          true
      }

    val isImplicit = sym.is(Flags.Implicit)
    val name = nameString(sym)
    val implicitKeyword = if (isImplicit) "implicit " else ""
    keyString(sym) match {
      case key @ ("var" | "val") =>
        s"$key $name: "
      case "" =>
        s"$implicitKeyword$name: "
      case key if isNullary =>
        s"$key $name: "
      case key =>
        s"$key $name"
    }
  }
}
