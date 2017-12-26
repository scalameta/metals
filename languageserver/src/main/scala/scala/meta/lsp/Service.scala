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

sealed trait Message
object Message {
  private case class IndeterminateMessage(
      method: String,
      params: Option[JsValue],
      id: Option[RequestId]
  )
  implicit val reads: Reads[Message] = Json.reads[IndeterminateMessage].map {
    case IndeterminateMessage(method, params, Some(id)) =>
      Request(method, params, id)
    case IndeterminateMessage(method, params, None) =>
      Notification(method, params)
  }
}
case class Request(method: String, params: Option[JsValue], id: RequestId)
    extends Message {
  def toError(code: ErrorCode, message: String): Response =
    Response.error(ErrorObject(code, message, None), id)
}
case class Notification(method: String, params: Option[JsValue]) extends Message

sealed trait Response {
  def toTask: Task[Response] = Task.eval(this)
}
object Response {
  case class Success(result: JsValue, id: RequestId) extends Response
  object Success {
    implicit val format: OFormat[Success] = Json.format[Success]
  }
  case class Error(error: ErrorObject, id: RequestId) extends Response
  object Error {
    implicit val format: OFormat[Error] = Json.format[Error]
  }
  case object Empty extends Response
  def empty: Response = Empty
  def success(result: JsValue, id: RequestId): Response =
    Success(result, id)
  def error(error: ErrorObject, id: RequestId): Response =
    Error(error, id)
  def internalError(message: String, id: RequestId): Response =
    Error(ErrorObject(ErrorCode.InternalError, message, None), id)
  def invalidRequest(message: String): Response =
    Error(
      ErrorObject(ErrorCode.InvalidParams, message, None),
      RequestId.Null
    )
  def cancelled(id: JsValue): Response =
    Error(
      ErrorObject(ErrorCode.RequestCancelled, "", None),
      id.asOpt[RequestId].getOrElse(RequestId.Null)
    )
  def parseError(message: String): Response =
    Error(ErrorObject(ErrorCode.ParseError, message, None), RequestId.Null)
}

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
object ErrorObject {
  implicit val format: OFormat[ErrorObject] = Json.format[ErrorObject]
}
sealed abstract class ErrorCode(val value: Int) extends IntEnumEntry
case object ErrorCode
    extends IntEnum[ErrorCode]
    with IntPlayJsonValueEnum[ErrorCode] {
  case object ParseError extends ErrorCode(-32700)
  case object InvalidRequest extends ErrorCode(-32600)
  case object MethodNotFound extends ErrorCode(-32601)
  case object InvalidParams extends ErrorCode(-32602)
  case object InternalError extends ErrorCode(-32603)
  case object ServerError extends ErrorCode(-32000)
  case object RequestCancelled extends ErrorCode(-32800)
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
              Response.success(Json.toJson(response), request.id)
            case Left(err) =>
              err
          }
          .onErrorRecover {
            case NonFatal(e) =>
              request.toError(ErrorCode.InternalError, e.getMessage)
          }
    }
  def handle(a: A): Task[Either[Response, B]]
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

case class CancelParams(id: JsValue)
object CancelParams {
  implicit val format: OFormat[CancelParams] = Json.format[CancelParams]
}
