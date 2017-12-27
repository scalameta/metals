package scala.meta.lsp

import play.api.libs.json._

sealed trait Response
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
  def error(error: ErrorObject, id: RequestId): Response.Error =
    Error(error, id)
  def internalError(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.InternalError, message, None), id)
  def invalidRequest(message: String): Response.Error =
    Error(
      ErrorObject(ErrorCode.InvalidParams, message, None),
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
