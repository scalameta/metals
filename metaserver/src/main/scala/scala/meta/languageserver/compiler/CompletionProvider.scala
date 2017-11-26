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
import scala.meta.languageserver.Compiler

object CompletionProvider extends LazyLogging {
  def empty: CompletionList = CompletionList(isIncomplete = false, Nil)

  def completions(
      compiler: Global,
      cursor: Cursor
  ): CompletionList = {
    val unit = Compiler.addCompilationUnit(
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
          detail = Some(r.sym.signatureString)
        )
      }
    }
    CompletionList(isIncomplete = false, items = items.result())
  }

}
