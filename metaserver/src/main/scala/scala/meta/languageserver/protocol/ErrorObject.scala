package scala.meta.languageserver.protocol

import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.OFormat

case class ErrorObject(code: ErrorCode, message: String, data: Option[JsValue])

object ErrorObject {
  implicit val format: OFormat[ErrorObject] = Json.format[ErrorObject]
}
