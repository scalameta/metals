package scala.meta.internal.pc

import dotty.tools.dotc.printing.PlainPrinter
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Flags
import scala.language.implicitConversions

class SymbolPrinter(implicit ctx: Context) extends PlainPrinter(ctx) {

  def fullDefinition(sym: Symbol, tpe: Type) = {

    def isNullary = tpe match {
      case tpe: (MethodType | PolyType) =>
        false
      case other =>
        true
    }

    val isImplicit = sym.is(Flags.Implicit)
    val name = sym.name
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
