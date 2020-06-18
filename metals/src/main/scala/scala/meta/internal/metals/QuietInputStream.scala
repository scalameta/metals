package scala.meta.internal.metals

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A delegate input stream that gracefully fails when the underlying stream is closed.
 *
 * This class exists to suppress lsp4j logs like this: {{{
 *   WARNING: Failed to send notification message.
 * org.eclipse.lsp4j.jsonrpc.JsonRpcException: java.io.IOException: Stream Closed
 *  at org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer.consume(StreamMessageConsumer.java:72)
 *  at org.eclipse.lsp4j.jsonrpc.Launcher$Builder.lambda$wrapMessageConsumer$0(Launcher.java:312)
 *  at or
 * }}}
 */
class QuietInputStream(underlying: InputStream, name: String)
    extends FilterInputStream(underlying) {
  override def read(): Int = {
    try {
      underlying.read()
    } catch {
      case e: IOException =>
        scribe.debug(s"closed: $name", e)
        -1
    }
  }
}
