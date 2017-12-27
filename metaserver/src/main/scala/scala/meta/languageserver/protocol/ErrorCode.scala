package scala.meta.languageserver.protocol

import scala.collection.immutable.IndexedSeq
import enumeratum.values.IntEnum
import enumeratum.values.IntEnumEntry
import enumeratum.values.IntPlayJsonValueEnum

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
  val values: IndexedSeq[ErrorCode] = findValues
}
