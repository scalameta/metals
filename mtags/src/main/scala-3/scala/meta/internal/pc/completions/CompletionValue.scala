package scala.meta.internal.pc
package completions

import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames.ShortName

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
import org.eclipse.lsp4j.Range

sealed trait CompletionValue:
  def label: String
  def snippetSuffix: Option[String] = None

  final def completionItemKind(using Context): CompletionItemKind =
    this match
      case _: CompletionValue.Keyword => CompletionItemKind.Keyword
      case _: CompletionValue.NamedArg => CompletionItemKind.Field
      case _: CompletionValue.Override => CompletionItemKind.Method
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

  final def lspTags(using Context): List[CompletionItemTag] =
    forSymOnly(
      sym =>
        if sym.isDeprecated then List(CompletionItemTag.Deprecated) else Nil,
      Nil
    )

  final def description(printer: MetalsPrinter): String =
    this match
      case _: CompletionValue.Override =>
        "" // Override doesn't need description as it already has full signature in label
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

  case class Compiler(
      label: String,
      symbol: Symbol,
      override val snippetSuffix: Option[String]
  ) extends Symbolic
  case class Scope(label: String, symbol: Symbol) extends Symbolic
  case class Workspace(label: String, symbol: Symbol) extends Symbolic

  /**
   * @param shortenedNames shortened type names by `Printer`. This field should be used for autoImports
   * @param start Starting position of the completion
   *              this is needed, because for OverrideCompletion, completionPos
   *              doesn't capture the "correct" starting position. For example,
   *              when we type `override def fo@@` (where `@@` we invoke completion)
   *              `completionPos` is `fo`, instead of `override def fo`.
   */
  case class Override(
      label: String,
      value: String,
      symbol: Symbol,
      shortenedNames: List[ShortName],
      filterText: String,
      start: Int
  ) extends Symbolic:
  end Override

  case class NamedArg(label: String, tpe: Type) extends CompletionValue
  case class Keyword(label: String, insertText: String) extends CompletionValue

  def namedArg(label: String, sym: Symbol)(using Context): CompletionValue =
    NamedArg(label, sym.info.widenTermRefExpr)

  def keyword(label: String, insertText: String): CompletionValue =
    Keyword(label, insertText)

  def workspace(label: String, sym: Symbol): CompletionValue =
    Workspace(label, sym)

  def scope(label: String, sym: Symbol): CompletionValue =
    Scope(label, sym)
end CompletionValue
