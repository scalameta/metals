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

class CompletionProvider(notifications: Notifications) extends LazyLogging {
  def empty: CompletionList = {
    notifications.showMessage(
      MessageType.Warning,
      "Run project/config:scalametaEnableCompletions to setup completion for this " +
        "config.in(project) or *:scalametaEnableCompletions for all projects/configurations"
    )
    Nil
    CompletionList(isIncomplete = false, Nil)
  }

  def completions(
      compiler: Global,
      position: Position
  ): CompletionList = {
    pprint.log(compiler.typedTreeAt(position))
    val isUsedLabel = mutable.Set.empty[String]
    val items = safeCompletionsAt(compiler, position)
      .flatMap { r =>
        val label = r.symNameDropLocal.decoded
        if (!isUsedLabel(label)) {
          isUsedLabel += label
          CompletionItem(
            label = label,
            detail = Some(r.sym.signatureString)
          ) :: Nil
        } else Nil
      }
    CompletionList(isIncomplete = false, items = items)
  }

}
