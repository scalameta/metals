package langserver.core

import langserver.messages.PublishDiagnostics
import langserver.messages.ShowMessageParams
import langserver.types.Diagnostic
import langserver.types.MessageType

/** Stub interface for Connection.showMessage */
trait Notifications {
  def publishDiagnostics(params: PublishDiagnostics): Unit
  final def publishDiagnostics(
      uri: String,
      diagnostics: Seq[Diagnostic]
  ): Unit = publishDiagnostics(PublishDiagnostics(uri, diagnostics))

  def showMessage(params: ShowMessageParams): Unit
  final def showMessage(
      tpe: MessageType,
      message: String
  ): Unit = showMessage(ShowMessageParams(tpe, message))
}
object Notifications {
  val empty: Notifications = new Notifications {
    override def showMessage(
        params: ShowMessageParams
    ): Unit = ()
    override def publishDiagnostics(
        publishDiagnostics: PublishDiagnostics
    ): Unit = ()
  }
}
