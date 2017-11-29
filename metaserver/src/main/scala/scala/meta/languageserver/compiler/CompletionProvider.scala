package scala.meta.languageserver.compiler

import scala.collection.mutable
import scala.meta.languageserver.compiler.CompilerUtils._
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

    def computeRelevance(
        sym: Symbol,
        viaView: Symbol,
        inherited: Boolean
    ): Int = {
      var relevance = 0
      if (sym.isLocalToBlock) relevance += 10
      if (sym.hasGetter) relevance += 5
      if (!inherited) relevance += 10
      if (viaView == NoSymbol) relevance += 20
      if (!sym.hasPackageFlag) relevance += 30
      if (sym.isCaseAccessor) relevance += 10
      if (sym.isPublic) relevance += 10
      if (sym.owner != definitions.AnyClass && sym.owner != definitions.AnyRefClass && sym.owner != definitions.ObjectClass)
        relevance += 40
      if (sym.owner != null && sym.owner.isCaseClass && sym.nameString == "copy") {
        relevance -= 10
      }
      relevance
    }

    val unit = ScalacProvider.addCompilationUnit(
      global = compiler,
      code = cursor.contents,
      filename = cursor.uri,
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
