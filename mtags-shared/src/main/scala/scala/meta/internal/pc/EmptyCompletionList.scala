package scala.meta.internal.pc

import java.{util => ju}

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList

object EmptyCompletionList {
  def apply(): CompletionList = {
    val items = new CompletionList(new ju.LinkedList[CompletionItem]())
    items.setIsIncomplete(true)
    items
  }

}
