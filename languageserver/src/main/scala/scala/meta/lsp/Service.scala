package scala.meta.lsp

import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging
import enumeratum.values.IntEnum
import enumeratum.values.IntEnumEntry
import enumeratum.values.IntPlayJsonValueEnum
import monix.eval.Task
import monix.reactive.Observable
import play.api.libs.json.JsNull
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import play.api.libs.json._

sealed trait RPC
case class Request(method: String, params: Option[JsValue], id: RequestId)
    extends RPC {
  def toError(code: ErrorCode, message: String): ErrorResponse =
    ErrorResponse(ErrorObject(code, message, None), id)
}
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

case class MethodRequestService(
    method: String,
    handler: JsonRequestService
)
case class MethodNotificationService(
    method: String,
    handler: JsonNotificationService
)
trait JsonRequestService {
  def handleRequest(request: Request): Task[Response]
}
abstract class RequestService[A: Reads, B: Writes] extends JsonRequestService {
  override def handleRequest(request: Request): Task[Response] =
    request.params.getOrElse(JsNull).validate[A] match {
      case err: JsError =>
        Task.eval(request.toError(ErrorCode.InvalidParams, err.toString))
      case JsSuccess(value, _) =>
        handle(value)
          .map[Response] {
            case Right(response) =>
              SuccessResponse(Json.toJson(response), request.id)
            case Left(err) =>
              err
          }
          .onErrorRecover {
            case NonFatal(e) =>
              request.toError(ErrorCode.InternalError, e.getMessage)
          }
    }
  def handle(a: A): Task[Either[ErrorResponse, B]]
}
trait JsonNotificationService {
  def handleNotification(notification: Notification): Task[Unit]
  Observable.fromInputStream(???)
}

trait Service extends JsonRequestService with JsonNotificationService

class CompositeService(
    notifications: List[MethodNotificationService],
    requests: List[MethodRequestService]
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
        Task.eval(
          request.toError(
            ErrorCode.MethodNotFound,
            s"Method '${request.method}' not found, expected one of ${rs.keys.mkString(", ")}"
          )
        )
      case Some(service) =>
        service.handler.handleRequest(request)
    }
}
