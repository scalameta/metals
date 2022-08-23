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
import org.eclipse.lsp4j.TextEdit

sealed trait CompletionValue:
  def label: String
  def insertText: Option[String] = None
  def snippetSuffix: Option[String] = None
  def additionalEdits: List[TextEdit] = Nil
  def range: Option[Range] = None
  def filterText: Option[String] = None

  final def completionItemKind(using Context): CompletionItemKind =
    this match
      case _: CompletionValue.Keyword => CompletionItemKind.Keyword
      case _: CompletionValue.Document => CompletionItemKind.Snippet
      case _: CompletionValue.NamedArg => CompletionItemKind.Field
      case _: CompletionValue.Override => CompletionItemKind.Method
      case _: CompletionValue.Extension => CompletionItemKind.Method
      case _: CompletionValue.Autofill => CompletionItemKind.Enum
      case v: (CompletionValue.Compiler | CompletionValue.Workspace |
            CompletionValue.Scope | CompletionValue.Interpolator) =>
        val symbol = v.symbol
        if symbol.is(Package) || symbol.is(Module) then
          // No CompletionItemKind.Package (https://github.com/Microsoft/language-server-protocol/issues/155)
          CompletionItemKind.Module
        else if symbol.isConstructor then CompletionItemKind.Constructor
        else if symbol.isClass then CompletionItemKind.Class
        else if symbol.is(Mutable) then CompletionItemKind.Variable
        else if symbol.is(Method) then CompletionItemKind.Method
        else CompletionItemKind.Field
      case _: CompletionValue.FileSystemMember => CompletionItemKind.File
  end completionItemKind

  final def lspTags(using Context): List[CompletionItemTag] =
    forSymOnly(
      sym =>
        if sym.isDeprecated then List(CompletionItemTag.Deprecated) else Nil,
      Nil,
    )

  final def description(printer: MetalsPrinter)(using Context): String =
    this match
      case _: CompletionValue.Override =>
        "" // Override doesn't need description as it already has full signature in label
      case ext: CompletionValue.Extension =>
        s"${printer.completionSymbol(ext.symbol)} (extension)"
      case interpolator: CompletionValue.Interpolator
          if interpolator.isExtension =>
        s"${printer.completionSymbol(interpolator.symbol)} (extension)"
      case so: CompletionValue.Symbolic =>
        printer.completionSymbol(so.symbol)
      case CompletionValue.NamedArg(label, tpe) =>
        printer.tpe(tpe)
      case CompletionValue.Document(_, _, desc) => desc
      case _ => ""

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
      override val snippetSuffix: Option[String],
  ) extends Symbolic
  case class Scope(label: String, symbol: Symbol) extends Symbolic
  case class Workspace(
      label: String,
      symbol: Symbol,
      override val snippetSuffix: Option[String],
  ) extends Symbolic

  /**
   * CompletionValue for extension methods via SymbolSearch
   */
  case class Extension(
      label: String,
      symbol: Symbol,
      override val snippetSuffix: Option[String],
  ) extends Symbolic

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
      override val filterText: Option[String],
      start: Int,
  ) extends Symbolic:
  end Override

  case class NamedArg(
      label: String,
      tpe: Type,
  ) extends CompletionValue
  case class Autofill(
      value: String
  ) extends CompletionValue:
    override def label: String = "Autofill with default values"
  case class Keyword(label: String, override val insertText: Option[String])
      extends CompletionValue

  case class FileSystemMember(
      filename: String,
      override val range: Option[Range],
      isDirectory: Boolean,
  ) extends CompletionValue:
    override def label: String = filename
    override def insertText: Option[String] = Some(filename.stripSuffix(".sc"))

  case class Interpolator(
      symbol: Symbol,
      label: String,
      override val insertText: Option[String],
      override val additionalEdits: List[TextEdit],
      override val range: Option[Range],
      override val filterText: Option[String],
      isWorkspace: Boolean = false,
      isExtension: Boolean = false,
  ) extends Symbolic

  case class Document(label: String, doc: String, description: String)
      extends CompletionValue

  def namedArg(label: String, sym: Symbol)(using
      Context
  ): CompletionValue =
    NamedArg(label, sym.info.widenTermRefExpr)

  def keyword(label: String, insertText: String): CompletionValue =
    Keyword(label, Some(insertText))

  def document(
      label: String,
      insertText: String,
      description: String,
  ): CompletionValue =
    Document(label, insertText, description)

  def scope(label: String, sym: Symbol): CompletionValue =
    Scope(label, sym)
end CompletionValue
