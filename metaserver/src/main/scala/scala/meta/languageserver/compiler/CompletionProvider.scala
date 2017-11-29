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
    import compiler.CompletionResult

    def completionItemKind(r: CompletionResult#M): Int = {
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

    val unit = ScalacProvider.addCompilationUnit(
      global = compiler,
      code = cursor.contents,
      filename = cursor.uri,
      cursor = Some(cursor.offset)
    )
    val position = unit.position(cursor.offset)
    val isUsedLabel = mutable.Set.empty[String]
    val items = List.newBuilder[CompletionItem]
    safeCompletionsAt(compiler, position).foreach { r =>
      val label = r.symNameDropLocal.decoded
      if (!isUsedLabel(label)) {
        isUsedLabel += label
        items += CompletionItem(
          label = label,
          detail = Some(r.sym.signatureString),
          kind = Some(completionItemKind(r))
        )
      }
    }
    CompletionList(isIncomplete = false, items = items.result())
  }

}
