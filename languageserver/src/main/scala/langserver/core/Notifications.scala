package langserver.core

import langserver.types.MessageType

/** Stub interface for Connection.showMessage */
trait Notifications {
  def showMessage(tpe: MessageType, message: String): Unit
}
object Notifications {
  val empty: Notifications = (_, _) => ()
}
