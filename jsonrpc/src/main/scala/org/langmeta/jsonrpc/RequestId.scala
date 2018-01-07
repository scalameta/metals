package org.langmeta.jsonrpc

import io.circe.Json
import io.circe.Decoder
import io.circe.Encoder

sealed trait RequestId
object RequestId {
  def apply(n: Int): RequestId.Number =
    RequestId.Number(Json.fromBigDecimal(BigDecimal(n)))
  implicit val decoder: Decoder[RequestId] = Decoder.decodeJson.map {
    case s if s.isString => RequestId.String(s)
    case n if n.isNumber => RequestId.Number(n)
    case n if n.isNull => RequestId.Null
  }
  implicit val encoder: Encoder[RequestId] = Encoder.encodeJson.contramap {
    case RequestId.Number(v) => v
    case RequestId.String(v) => v
    case RequestId.Null => Json.Null
  }
  // implicit val encoder: Decoder =
  case class Number(value: Json) extends RequestId
  case class String(value: Json) extends RequestId
  case object Null extends RequestId
}
