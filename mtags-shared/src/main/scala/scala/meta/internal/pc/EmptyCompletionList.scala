package scala.meta.internal.pc

import org.eclipse.lsp4j.{CompletionItem, CompletionList}

import java.{util => ju}

object EmptyCompletionList {
  def apply(): CompletionList = {
    val items = new CompletionList(new ju.LinkedList[CompletionItem]())
    items.setIsIncomplete(true)
    items
  }

}
