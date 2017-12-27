package scala.meta.languageserver.protocol

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import play.api.libs.json.JsError
import play.api.libs.json.JsNull
import play.api.libs.json.JsSuccess
import play.api.libs.json.Reads

trait JsonNotificationService {
  def handleNotification(notification: Notification): Task[Unit]
}
trait NamedNotificationService extends JsonNotificationService {
  def method: String
}
abstract class NotificationService[A: Reads](val method: String)
    extends NamedNotificationService
    with LazyLogging {
  def handle(request: A): Task[Unit]
  override def handleNotification(notification: Notification): Task[Unit] =
    notification.params.getOrElse(JsNull).validate[A] match {
      case err: JsError =>
        Task.eval {
          logger.error(
            s"Failed to parse notification $notification. Errors: $err"
          )
        }
      case JsSuccess(value, _) =>
        handle(value)
    }
}
object NotificationService {
  def method[A: Reads](
      name: String
  )(f: A => Task[Unit]): NotificationService[A] =
    new NotificationService[A](name) {
      override def handle(request: A): Task[Unit] = f(request)
    }
}
