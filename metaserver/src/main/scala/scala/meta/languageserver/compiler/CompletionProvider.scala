package scala.meta.languageserver.compiler

import scala.collection.mutable
import scala.meta.languageserver.compiler.CompilerUtils._
import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.messages.CompletionList
import langserver.messages.MessageType
import langserver.types.CompletionItem
import langserver.types.CompletionItemKind

object CompletionProvider extends LazyLogging {
  def empty: CompletionList = CompletionList(isIncomplete = false, Nil)

  def completions(
      compiler: Global,
      cursor: Cursor
  ): CompletionList = {
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
          kind = completionItemKind(compiler)(r)
        )
      }
    }
    CompletionList(isIncomplete = false, items = items.result())
  }

  private def completionItemKind(c: Global)(r: c.CompletionResult#M): Option[Int] = {
    if (r.sym.isPackage) Some(CompletionItemKind.Module)
    else if (r.sym.isModuleOrModuleClass) Some(CompletionItemKind.Module)
    else if (r.sym.isTraitOrInterface) Some(CompletionItemKind.Interface)
    else if (r.sym.isClass) Some(CompletionItemKind.Class)
    else if (r.sym.isPackageObject) Some(CompletionItemKind.Module)
    else if (r.sym.isMethod) Some(CompletionItemKind.Method)
    else if (r.sym.isField && r.sym.owner != null && r.sym.owner.isCaseClass) Some(CompletionItemKind.Field)
    else if (r.sym.isVal) Some(CompletionItemKind.Value)
    else if (r.sym.isVar) Some(CompletionItemKind.Variable)
    else None
  }

}
