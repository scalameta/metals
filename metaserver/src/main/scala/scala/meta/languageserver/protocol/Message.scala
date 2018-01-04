package scala.meta.languageserver.protocol

import monix.eval.Task
import io.circe.Json
import io.circe.Decoder
import io.circe.generic.JsonCodec
import cats.syntax.either._

sealed trait Message
object Message {
  implicit val decoder: Decoder[Message] = Decoder.decodeJsonObject.emap { obj =>
    val json = Json.fromJsonObject(obj)
    val result = if (obj.contains("id"))
      if (obj.contains("error")) json.as[Response.Error]
      else if (obj.contains("result")) json.as[Response.Success]
      else json.as[Request]
    else json.as[Notification]
    result.leftMap(_.toString)
  }
}

@JsonCodec case class Request(method: String, params: Option[Json], id: RequestId)
    extends Message {
  def toError(code: ErrorCode, message: String): Response =
    Response.error(ErrorObject(code, message, None), id)
}

@JsonCodec case class Notification(method: String, params: Option[Json]) extends Message

@JsonCodec sealed trait Response extends Message {
  def isSuccess: Boolean = this.isInstanceOf[Response.Success]
}
object Response {
  @JsonCodec case class Success(result: Json, id: RequestId) extends Response
  @JsonCodec case class Error(error: ErrorObject, id: RequestId) extends Response
  case object Empty extends Response
  def empty: Response = Empty
  def ok(result: Json, id: RequestId): Response =
    success(result, id)
  def okAsync[T](value: T): Task[Either[Response.Error, T]] =
    Task(Right(value))
  def success(result: Json, id: RequestId): Response =
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
  def cancelled(id: Json): Response.Error =
    Error(
      ErrorObject(ErrorCode.RequestCancelled, "", None),
      id.as[RequestId].getOrElse(RequestId.Null)
    )
  def parseError(message: String): Response.Error =
    Error(ErrorObject(ErrorCode.ParseError, message, None), RequestId.Null)
  def methodNotFound(message: String, id: RequestId): Response.Error =
    Error(ErrorObject(ErrorCode.MethodNotFound, message, None), id)
}
