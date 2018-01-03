package scala.meta.languageserver.protocol

import scala.meta.languageserver.PlayJsonEnrichments._
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.DidChangeTextDocumentParams
import langserver.messages.InitializeParams
import langserver.messages.InitializeResult
import monix.eval.Task
import play.api.libs.json._

trait Service[A, B] {
  def handle(request: A): Task[B]
}
trait MethodName {
  def methodName: String
}

trait JsonRpcService extends Service[Message, Response]
trait NamedJsonRpcService extends JsonRpcService with MethodName

object Service extends LazyLogging {

  def notification[A: Reads](
      method: String
  )(f: Service[A, Unit]): NamedJsonRpcService =
    new NamedJsonRpcService {
      private def fail(msg: String): Task[Response] = Task {
        logger.error(msg)
        Response.empty
      }
      override def handle(message: Message): Task[Response] = message match {
        case Notification(`method`, params) =>
          params.getOrElse(JsNull).validate[A] match {
            case err: JsError =>
              fail(s"Failed to parse notification $message. Errors: $err")
            case JsSuccess(value, _) =>
              f.handle(value).map(_ => Response.empty)
          }
        case Notification(invalidMethod, _) =>
          fail(s"Expected method '$method', obtained '$invalidMethod'")
        case request: Request =>
          fail(
            s"Expected notification with no ID, obtained request with id $request"
          )
      }

      override def methodName: String = method
    }

  def request[A: Reads, B: Writes](method: String)(
      f: Service[A, B]
  ): NamedJsonRpcService = new NamedJsonRpcService {
    override def handle(message: Message): Task[Response] = message match {
      case Request(`method`, params, id) =>
        params.getOrElse(JsNull).validate[A] match {
          case err: JsError =>
            Task(Response.invalidParams(err.show, id))
          case JsSuccess(value, _) =>
            f.handle(value).map { response =>
              Response.ok(Json.toJson(response), id)
            }
        }
      case Request(invalidMethod, _, id) =>
        Task(Response.methodNotFound(invalidMethod, id))
      case _ =>
        Task(Response.invalidRequest(s"Expected request, obtained $message"))
    }
    override def methodName: String = method
  }

}

object Services {
  val init: Services = Services(scala.Nil, scala.Nil)
}

case class Services(
    requests: List[NamedRequestService],
    notifications: List[NamedNotificationService]
) extends Router {
  override def requestAsync[A: Reads, B: Writes](method: String)(
      f: A => Task[Either[Response.Error, B]]
  ): Services =
    Services(
      RequestService.request[A, B](method)(f) :: requests,
      notifications
    )
  override def notificationAsync[A: Reads](method: String)(
      f: A => Task[Unit]
  ): Services =
    Services(
      requests,
      NotificationService.notification[A](method)(f) :: notifications
    )
}
