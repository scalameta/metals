package langserver.core

import com.typesafe.scalalogging.LazyLogging
import enumeratum.values.IntEnum
import enumeratum.values.IntEnumEntry
import enumeratum.values.IntPlayJsonValueEnum
import monix.eval.Task
import play.api.libs.json.JsNull
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import play.api.libs.json._

sealed trait RPC
case class Request(method: String, params: Option[JsValue], id: RequestId)
    extends RPC
case class Notification(method: String, params: JsValue) extends RPC

sealed trait Response extends RPC
case class SuccessResponse(result: JsValue, id: RequestId) extends Response
case class ErrorResponse(error: ErrorObject, id: RequestId) extends Response

sealed trait RequestId
object RequestId {
  implicit val format: Format[RequestId] = Format[RequestId](
    Reads {
      case num: JsNumber => JsSuccess(Number(num))
      case JsNull => JsSuccess(Null)
      case value: JsString => JsSuccess(String(value))
      case els => JsError(s"Expected number, string or null. Obtained $els")
    },
    Writes {
      case Number(value) => value
      case String(value) => value
      case Null => JsNull
    }
  )
  case class Number(value: JsNumber) extends RequestId
  case class String(value: JsString) extends RequestId
  case object Null extends RequestId
}

case class ErrorObject(code: ErrorCode, message: String, data: Option[JsValue])
sealed abstract class ErrorCode(val value: Int) extends IntEnumEntry
case object ErrorCode
    extends IntEnum[ErrorCode]
    with IntPlayJsonValueEnum[ErrorCode] {

  /**
   * Invalid JSON was received by the server.
   *
   * An error occurred on the server while parsing the JSON text.
   */
  case object ParseError extends ErrorCode(-32700)

  /** The JSON sent is not a valid Request object. */
  case object InvalidRequest extends ErrorCode(-32600)

  /** The method does not exist / is not available. */
  case object MethodNotFound extends ErrorCode(-32601)

  /** Invalid method parameter(s). */
  case object InvalidParams extends ErrorCode(-32602)

  /** Internal JSON-RPC error. */
  case object InternalError extends ErrorCode(-32603)

  /** Reserved for implementation-defined server-errors. */
  case object ServerError extends ErrorCode(-32000)

  val values: collection.immutable.IndexedSeq[ErrorCode] = findValues
}

trait Service
    extends RequestHandler[Request, Response]
    with NotificationHandler[Notification]

case class MethodRequestHandler(
    method: String,
    handler: RequestHandler[Request, Response]
)
case class MethodNotificationHandler(
    method: String,
    handler: NotificationHandler[Notification]
)
trait RequestHandler[-A, +B] {
  def handleRequest(request: A): Task[B]
}
trait NotificationHandler[-A] {
  def handleNotification(notification: A): Task[Unit]
}

class CompositeService(
    notifications: List[MethodNotificationHandler],
    requests: List[MethodRequestHandler]
) extends Service
    with LazyLogging {
  private val ns = notifications.iterator.map(n => n.method -> n).toMap
  private val rs = requests.iterator.map(n => n.method -> n).toMap
  override def handleNotification(notification: Notification): Task[Unit] =
    ns.get(notification.method) match {
      case Some(service) => service.handler.handleNotification(notification)
      case None =>
        logger.warn(s"Method not found '${notification.method}'")
        Task.unit // No way to report error on notifications
    }

  override def handleRequest(request: Request): Task[Response] =
    rs.get(request.method) match {
      case None =>
        Task.now(
          ErrorResponse(
            ErrorObject(
              ErrorCode.MethodNotFound,
              s"Method '${request.method}' not found, expected one of ${rs.keys.mkString(", ")}",
              None
            ),
            request.id
          )
        )
      case Some(service) =>
        service.handler.handleRequest(request)
    }

}
