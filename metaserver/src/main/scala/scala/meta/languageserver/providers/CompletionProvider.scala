package scala.meta.languageserver.providers

import scala.collection.mutable
import scala.meta.languageserver.compiler.Cursor
import scala.meta.languageserver.compiler.ScalacProvider
import scala.meta.languageserver.compiler.CompilerEnrichments._
import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.messages.CompletionList
import langserver.types.CompletionItem
import langserver.types.CompletionItemKind

object CompletionProvider extends LazyLogging {
  def empty: CompletionList = CompletionList(isIncomplete = false, Nil)

  def completions(
      compiler: Global,
      cursor: Cursor
  ): CompletionList = {
    import compiler._

    def completionItemKind(r: CompletionResult#M): CompletionItemKind = {
      if (r.sym.isPackage) CompletionItemKind.Module
      else if (r.sym.isPackageObject) CompletionItemKind.Module
      else if (r.sym.isModuleOrModuleClass) CompletionItemKind.Module
      else if (r.sym.isTraitOrInterface) CompletionItemKind.Interface
      else if (r.sym.isClass) CompletionItemKind.Class
      else if (r.sym.isMethod) CompletionItemKind.Method
      else if (r.sym.isCaseAccessor) CompletionItemKind.Field
      else if (r.sym.isVal) CompletionItemKind.Value
      else if (r.sym.isVar) CompletionItemKind.Variable
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
