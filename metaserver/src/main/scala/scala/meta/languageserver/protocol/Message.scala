package scala.meta.languageserver.protocol

import monix.eval.Task
import play.api.libs.json._

sealed trait Message
object Message {
  implicit val reads: Reads[Message] = Reads {
    case json @ JsObject(map) =>
      if (map.contains("id")) {
        if (map.contains("error")) json.validate[Response.Error]
        else if (map.contains("result")) json.validate[Response.Success]
        else json.validate[Request]
      } else json.validate[Notification]
    case els =>
      JsError(s"Expected object, obtained $els")
  }
}

case class Request(method: String, params: Option[JsValue], id: RequestId)
    extends Message {
  def toError(code: ErrorCode, message: String): Response =
    Response.error(ErrorObject(code, message, None), id)
}
object Request {
  implicit val format: OFormat[Request] = Json.format[Request]
}

case class Notification(method: String, params: Option[JsValue]) extends Message
object Notification {
  implicit val format: OFormat[Notification] = Json.format[Notification]
}

sealed trait Response extends Message {
  def isSuccess: Boolean = this.isInstanceOf[Response.Success]
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
  def ok(result: JsValue, id: RequestId): Response =
    success(result, id)
  def okAsync[T](value: T): Task[Either[Response.Error, T]] =
    Task(Right(value))
  def success(result: JsValue, id: RequestId): Response =
    Success(result, id)
  def error(error: ErrorObject, id: RequestId): Response.Error =
    Error(error, id)
  def internalError(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.InternalError, message, None), id)
  def invalidParams(
      message: String,
      id: RequestId = RequestId.Null
  ): Response.Error =
    Error(ErrorObject(ErrorCode.InvalidParams, message, None), id)
  def invalidRequest(message: String): Response.Error =
    Error(
      ErrorObject(ErrorCode.InvalidRequest, message, None),
      RequestId.Null
    )
  def cancelled(id: JsValue): Response.Error =
    Error(
      ErrorObject(ErrorCode.RequestCancelled, "", None),
      id.asOpt[RequestId].getOrElse(RequestId.Null)
    )
  def parseError(message: String): Response.Error =
    Error(ErrorObject(ErrorCode.ParseError, message, None), RequestId.Null)
  def methodNotFound(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.MethodNotFound, message, None), id)
}
