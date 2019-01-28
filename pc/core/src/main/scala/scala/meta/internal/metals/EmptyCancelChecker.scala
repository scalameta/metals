package scala.meta.internal.metals

import org.eclipse.lsp4j.jsonrpc.CancelChecker

object EmptyCancelChecker extends CancelChecker {
  override def checkCanceled(): Unit = ()
}
