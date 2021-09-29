package scala.meta.internal.pc

import scala.meta.internal.pc.CompletionValue.Kind

import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.Completion

case class CompletionValue(
    label: String,
    symbol: Symbol,
    kind: Kind
)

object CompletionValue:

  enum Kind:
    case NamedArg, Workspace, Compiler, Scope

  def fromCompiler(completion: Completion): List[CompletionValue] =
    completion.symbols.map(CompletionValue(completion.label, _, Kind.Compiler))

  def namedArg(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.NamedArg)

  def workspace(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.Workspace)

  def scope(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.Scope)

end CompletionValue
