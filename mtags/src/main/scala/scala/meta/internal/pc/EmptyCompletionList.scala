package scala.meta.internal.pc

import org.eclipse.lsp4j.CompletionList
import java.{util => ju}
import org.eclipse.lsp4j.CompletionItem

object EmptyCompletionList {
  def apply(): CompletionList = {
    val items = new CompletionList(new ju.LinkedList[CompletionItem]())
    items.setIsIncomplete(true)
    items
  }

}
