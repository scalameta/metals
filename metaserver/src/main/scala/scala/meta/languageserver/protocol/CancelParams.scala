package scala.meta.languageserver.protocol

import play.api.libs.json.JsValue
import play.api.libs.json.Json
import play.api.libs.json.OFormat

case class CancelParams(id: JsValue)

object CancelParams {
  implicit val format: OFormat[CancelParams] = Json.format[CancelParams]
}