package scala.meta.lsp

import play.api.libs.json._

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