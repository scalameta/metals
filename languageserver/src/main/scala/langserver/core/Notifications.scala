package langserver.core

/** Stub interface for Connection.showMessage */
trait Notifications {
  def showMessage(tpe: Int, message: String): Unit
}
object Notifications {
  val empty: Notifications = (_, _) => ()
}
