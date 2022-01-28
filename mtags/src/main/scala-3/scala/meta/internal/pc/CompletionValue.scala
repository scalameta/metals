package scala.meta.internal.pc

import scala.meta.internal.pc.CompletionValue.Kind

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.Completion
import org.eclipse.lsp4j.CompletionItemKind

case class CompletionValue(
    label: String,
    symbol: Symbol,
    kind: Kind,
    isCustom: Boolean = false,
    insertText: Option[String] = None
):

  def completionItemKind(using ctx: Context): CompletionItemKind =
    if kind == CompletionValue.Kind.Keyword then CompletionItemKind.Keyword
    else if symbol.is(Package) || symbol.is(Module) then
      CompletionItemKind.Module // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
    else if symbol.isConstructor then CompletionItemKind.Constructor
    else if symbol.isClass then CompletionItemKind.Class
    else if symbol.is(Mutable) then CompletionItemKind.Variable
    else if symbol.is(Method) then CompletionItemKind.Method
    else CompletionItemKind.Field

end CompletionValue

object CompletionValue:

  enum Kind:
    case Keyword, NamedArg, Workspace, Compiler, Scope

  def fromCompiler(completion: Completion): List[CompletionValue] =
    completion.symbols.map(CompletionValue(completion.label, _, Kind.Compiler))

  def namedArg(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.NamedArg, isCustom = true)

  def keyword(label: String, insertText: String): CompletionValue =
    CompletionValue(
      label,
      NoSymbol,
      Kind.Keyword,
      isCustom = true,
      insertText = Some(insertText)
    )

  def workspace(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.Workspace)

  def scope(label: String, sym: Symbol): CompletionValue =
    CompletionValue(label, sym, Kind.Scope)

end CompletionValue
