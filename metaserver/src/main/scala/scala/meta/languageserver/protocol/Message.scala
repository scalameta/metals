package scala.meta.languageserver.protocol

import play.api.libs.json._

sealed trait Message {
  def method: String
}
object Message {
  private case class IndeterminateMessage(
      method: String,
      params: Option[JsValue],
      id: Option[RequestId]
  )
  implicit val reads: Reads[Message] = Reads {
    case json @ JsObject(map) =>
      if (map.contains("id")) json.validate[Request]
      else json.validate[Notification]
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
