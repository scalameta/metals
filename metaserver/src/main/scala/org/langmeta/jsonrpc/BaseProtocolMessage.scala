package org.langmeta.jsonrpc

import ScodecUtils.{bracketedBy, lookaheadIssue98Patch}
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs.{bytes, constant, list, optional}

case class BaseProtocolMessage(mimeType: Option[String], content: ByteVector)

object BaseProtocolMessage {
  val CONTENT_LENGTH_KEY = "Content-Length"
  val CONTENT_TYPE_KEY = "Content-Type"
  val codec: Codec[BaseProtocolMessage] =
    (bracketedBy("Content-Length:", "\r\n").xmap[Int](_.trim.toInt, _.toString)
      ~ optional(
        lookaheadIssue98Patch(constant('C')),
        bracketedBy("Content-Type:", "\r\n")
      ))
      .dropRight(constant(ByteVector('\r', '\n')))
      .consume {
        case (len, mime) =>
          bytes(len).xmap[BaseProtocolMessage](
            bytes => BaseProtocolMessage(mime, bytes),
            lsp => lsp.content
          )
      }(lsp => (lsp.content.length.toInt, lsp.mimeType))

  val listCodec: Codec[List[BaseProtocolMessage]] = list(codec)

  def fromJsonRpcBytes(bytes: Array[Byte]): BaseProtocolMessage =
    BaseProtocolMessage(None, ByteVector(bytes))
}
