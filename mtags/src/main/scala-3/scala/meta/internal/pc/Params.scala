package scala.meta.internal.pc

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Flags._
// maybe we can use Params.scala defined in scala2

case class Params(
    labels: Seq[String],
    kind: Params.Kind
)

object Params {
  enum Kind {
    case TypeParameterKind, NormalKind, ImplicitKind
  }

  def paramsKind(syms: List[Symbol])(using Context): Params.Kind = {
    syms match {
      case head :: _ =>
        if (head.isType) Kind.TypeParameterKind
        else if (head.is(Implicit)) Kind.ImplicitKind
        else Kind.NormalKind
      case Nil => Kind.NormalKind
    }
  }
}
