package scala.meta.languageserver.protocol

import java.io.InputStream

import monix.reactive.Observable
import monix.reactive.observables.ObservableLike.Transformer
import org.langmeta.jsonrpc.BaseProtocolMessage
import scodec.DecodeResult
import scodec.bits.BitVector

object BaseProtocolMessageParser {
  def fromInputStream(in: InputStream): Observable[BaseProtocolMessage] =
    Observable
      .fromInputStream(in)
      .transform(lspMessageExtractor)

  def lspMessageExtractor: Transformer[Array[Byte], BaseProtocolMessage] =
    obsBytes => {
      //Start with am empty decoderesult and pass along decoded messages plus remainder bits as needed
      val emptyDecodeResult =
        DecodeResult(List[BaseProtocolMessage](), BitVector.empty)
      obsBytes
        .scan(emptyDecodeResult) {
          case (DecodeResult(_, buf), newBytes) =>
            val currentBytes = buf ++ BitVector(newBytes)
            BaseProtocolMessage.listCodec
              .decode(currentBytes) //Decode if we can
              .getOrElse(DecodeResult(Nil, currentBytes)) //else save bytes for next try
        }
        .flatMap(decodeResult => Observable.fromIterable(decodeResult.value)) //emit once for each message
    }
}
