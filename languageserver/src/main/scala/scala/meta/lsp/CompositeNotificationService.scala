package scala.meta.lsp

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task

class CompositeNotificationService(
    notifications: List[NamedNotificationService]
) extends JsonNotificationService
    with LazyLogging {
  private val map = notifications.iterator.map(n => n.method -> n).toMap

  override def handleNotification(notification: Notification): Task[Unit] =
    map.get(notification.method) match {
      case Some(service) => service.handleNotification(notification)
      case None =>
        logger.warn(s"Method not found '${notification.method}'")
        Task.unit // No way to report error on notifications
    }
}
