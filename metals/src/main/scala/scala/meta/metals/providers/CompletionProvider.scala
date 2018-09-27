package scala.meta.metals.providers

import scala.collection.mutable
import scala.meta.metals.compiler.Cursor
import scala.meta.metals.compiler.ScalacProvider
import scala.meta.metals.compiler.CompilerEnrichments._
import scala.meta.lsp.CompletionItem
import scala.meta.lsp.CompletionList
import scala.tools.nsc.interactive.Global
import scala.meta.metals.MetalsLogger
import scala.meta.lsp.CompletionItemKind

object CompletionProvider extends MetalsLogger {
  def empty: CompletionList = CompletionList(isIncomplete = false, Nil)

  def completions(
      compiler: Global,
      cursor: Cursor
  ): CompletionList = {
    import compiler._

    def isFunction(symbol: Symbol): Boolean = {
      compiler.definitions.isFunctionSymbol(
        symbol.info.finalResultType.typeSymbol
      )
    }

    def completionItemKind(r: CompletionResult#M): CompletionItemKind = {
      val symbol = r.sym
      val symbolIsFunction = isFunction(symbol)
      if (symbol.hasPackageFlag) CompletionItemKind.Module
      else if (symbol.isPackageObject) CompletionItemKind.Module
      else if (symbol.isModuleOrModuleClass) CompletionItemKind.Module
      else if (symbol.isTraitOrInterface) CompletionItemKind.Interface
      else if (symbol.isClass) CompletionItemKind.Class
      else if (symbol.isMethod) CompletionItemKind.Method
      else if (symbol.isCaseAccessor) CompletionItemKind.Field
      else if (symbol.isVal && !symbolIsFunction) CompletionItemKind.Value
      else if (symbol.isVar && !symbolIsFunction) CompletionItemKind.Variable
      else if (symbol.isTypeParameterOrSkolem) CompletionItemKind.TypeParameter
      else if (symbolIsFunction) CompletionItemKind.Function
      else CompletionItemKind.Value
    }

    /** Computes the relative relevance of a symbol in the completion list
     * This is an adaptation of
     * https://github.com/scala-ide/scala-ide/blob/a17ace0ee1be1875b8992664069d8ad26162eeee/org.scala-ide.sdt.core/src/org/scalaide/core/completion/ProposalRelevanceCalculator.scala
     */
    def computeRelevance(
        sym: Symbol,
        viaView: Symbol,
        inherited: Boolean
    ): Int = {
      var relevance = 0
      // local symbols are more relevant
      if (sym.isLocalToBlock) relevance += 10
      // fields are more relevant than non fields
      if (sym.hasGetter) relevance += 5
      // non-inherited members are more relevant
      if (!inherited) relevance += 10
      // symbols not provided via an implicit are more relevant
      if (viaView == NoSymbol) relevance += 20
      if (!sym.hasPackageFlag) relevance += 30
      // accessors of case class members are more relevant
      if (sym.isCaseAccessor) relevance += 10
      // public symbols are more relevant
      if (sym.isPublic) relevance += 10
      // synthetic symbols are less relevant (e.g. `copy` on case classes)
      if (!sym.isSynthetic) relevance += 10
      // symbols whose owner is a base class are less relevant
      if (sym.owner != definitions.AnyClass && sym.owner != definitions.AnyRefClass && sym.owner != definitions.ObjectClass)
        relevance += 40
      relevance
    }

    val unit = ScalacProvider.addCompilationUnit(
      global = compiler,
      code = cursor.contents,
      filename = cursor.uri.value,
      cursor = Some(cursor.offset)
    )
    val position = unit.position(cursor.offset)
    val isUsedLabel = mutable.Set.empty[String]
    val items = List.newBuilder[CompletionItem]
    safeCompletionsAt(compiler, position)
      .sortBy {
        case TypeMember(sym, _, true, inherited, viaView) =>
          // logger.debug(s"Relevance of ${sym.name}: ${computeRelevance(sym, viaView, inherited)}")
          (-computeRelevance(sym, viaView, inherited), sym.nameString)
        case ScopeMember(sym, _, true, _) =>
          (-computeRelevance(sym, NoSymbol, false), sym.nameString)
        case r => (0, r.sym.nameString)
      }
      .zipWithIndex
      .foreach {
        case (r, idx) =>
          val label = r.symNameDropLocal.decoded
          if (!isUsedLabel(label)) {
            isUsedLabel += label
            items += CompletionItem(
              label = label,
              detail = Some(r.sym.signatureString),
              kind = Some(completionItemKind(r)),
              sortText = Some(f"${idx}%05d")
            )
          }
      }
    CompletionList(isIncomplete = false, items = items.result())
  }

}
