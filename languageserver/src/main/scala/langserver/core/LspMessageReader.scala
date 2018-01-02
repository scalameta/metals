package langserver.core

import langserver.messages.RawLspMessage
import monix.reactive.Observable
import scodec.DecodeResult
import scodec.bits.BitVector

/*
  Splits incoming bytes into unparsed RawLspMessages
 */
object LspMessageReader {
  def lspMessageExtractor: Observable[Array[Byte]] => Observable[List[RawLspMessage]] = obsBytes => {
    //Start with am empty decoderesult and pass along decoded messages plus remainder bits as needed
    val emptyDecodeResult: DecodeResult[List[RawLspMessage]] = DecodeResult(List[RawLspMessage](), BitVector.empty)
    obsBytes
      .scan(emptyDecodeResult) { case (DecodeResult(messages, buf), newBytes) =>
        RawLspMessage.listCodec.decode(buf ++ BitVector(newBytes))//Decode if we can
          .getOrElse(emptyDecodeResult.copy(remainder = buf ++ BitVector(newBytes))) //else save bytes for next try
      }.collect { case DecodeResult(messages, _) if messages.nonEmpty => messages } //Only forward nonEmpty lists
  }
}


