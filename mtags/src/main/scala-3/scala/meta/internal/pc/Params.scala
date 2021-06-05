package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Symbols.Symbol

case class Params(
    labels: Seq[String],
    kind: Params.Kind
)

object Params {
  enum Kind {
    case TypeParameter, Normal, Implicit, Using
  }

  def paramsKind(syms: List[Symbol])(using Context): Params.Kind = {
    syms match {
      case head :: _ =>
        if (head.isType) Kind.TypeParameter
        else if (head.is(Given)) Kind.Using
        else if (head.is(Implicit)) Kind.Implicit
        else Kind.Normal
      case Nil => Kind.Normal
    }
  }
}
