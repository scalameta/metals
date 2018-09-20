package scala.meta.metals.bsp

import scala.meta.lsp.LanguageClient
import monix.eval.Task
import monix.execution.CancelableFuture

trait BspConnector {
  type Connection = (LanguageClient, CancelableFuture[Unit])

  def openServerConnection(): Task[Either[String, Connection]]
}
