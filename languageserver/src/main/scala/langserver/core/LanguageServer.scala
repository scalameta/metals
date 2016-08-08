package langserver.core

import langserver.messages.InitializeParams

trait LanguageServer {
  val connection: Connection
  
  def initialize(params: InitializeParams): Unit
  
  def shutdown(): Unit
  
  def onDidChangeConfiguration(params: Any): Unit
}