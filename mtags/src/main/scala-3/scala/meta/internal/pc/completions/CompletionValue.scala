package scala.meta.internal.pc
package completions

import scala.meta.internal.pc.printer.MetalsPrinter

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Symbols.NoSymbol
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Completion
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.ParsedComment
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemTag

sealed trait CompletionValue:
  def label: String

  final def completionItemKind(using Context): CompletionItemKind =
    this match
      case _: CompletionValue.Keyword => CompletionItemKind.Keyword
      case _: CompletionValue.NamedArg => CompletionItemKind.Field
      case v: (CompletionValue.Compiler | CompletionValue.Workspace |
            CompletionValue.Scope) =>
        val symbol = v.symbol
        if symbol.is(Package) || symbol.is(Module) then
          // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
          CompletionItemKind.Module
        else if symbol.isConstructor then CompletionItemKind.Constructor
        else if symbol.isClass then CompletionItemKind.Class
        else if symbol.is(Mutable) then CompletionItemKind.Variable
        else if symbol.is(Method) then CompletionItemKind.Method
        else CompletionItemKind.Field
  end completionItemKind

  final def documentation(using Context): Option[String] =
    forSymOnly(ParsedComment.docOf(_).map(_.renderAsMarkdown), None)

  final def lspTags(using Context): List[CompletionItemTag] =
    forSymOnly(
      sym =>
        if sym.isDeprecated then List(CompletionItemTag.Deprecated) else Nil,
      Nil
    )

  final def description(printer: MetalsPrinter): String =
    this match
      case so: CompletionValue.Symbolic =>
        printer.completionSymbol(so.symbol)
      case CompletionValue.NamedArg(_, tpe) =>
        printer.tpe(tpe)
      case _: CompletionValue.Keyword => ""

  private def forSymOnly[A](f: Symbol => A, orElse: => A): A =
    this match
      case v: CompletionValue.Symbolic => f(v.symbol)
      case _ => orElse

end CompletionValue

object CompletionValue:

  sealed trait Symbolic extends CompletionValue:
    def symbol: Symbol

  case class Compiler(label: String, symbol: Symbol) extends Symbolic
  case class Scope(label: String, symbol: Symbol) extends Symbolic
  case class Workspace(label: String, symbol: Symbol) extends Symbolic

  case class NamedArg(label: String, tpe: Type) extends CompletionValue
  case class Keyword(label: String, insertText: String) extends CompletionValue

  def fromCompiler(completion: Completion): List[CompletionValue] =
    def undoBacktick(label: String): String =
      label.stripPrefix("`").stripSuffix("`")
    completion.symbols.map(Compiler(undoBacktick(completion.label), _))

  def namedArg(label: String, sym: Symbol)(using Context): CompletionValue =
    NamedArg(label, sym.info.widenTermRefExpr)

  def keyword(label: String, insertText: String): CompletionValue =
    Keyword(label, insertText)

  def workspace(label: String, sym: Symbol): CompletionValue =
    Workspace(label, sym)

  def scope(label: String, sym: Symbol): CompletionValue =
    Scope(label, sym)
end CompletionValue
