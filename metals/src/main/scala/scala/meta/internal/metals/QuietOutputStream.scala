package scala.meta.internal.metals

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A delegate output stream that gracefully fails when the underlying stream is closed.
 *
 * This class exists to suppress lsp4j logs like this: {{{
 *   WARNING: Failed to send notification message.
 * org.eclipse.lsp4j.jsonrpc.JsonRpcException: java.io.IOException: Stream Closed
 * 	at org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer.consume(StreamMessageConsumer.java:72)
 * 	at org.eclipse.lsp4j.jsonrpc.Launcher$Builder.lambda$wrapMessageConsumer$0(Launcher.java:312)
 * 	at or
 * }}}
 */
class QuietOutputStream(underlying: OutputStream, name: String)
    extends FilterOutputStream(underlying) {
  private var isClosed = false

  override def flush(): Unit = {
    try {
      super.flush()
    } catch {
      case _: IOException =>
    }
  }

  override def write(b: Int): Unit = {
    try {
      if (!isClosed) {
        underlying.write(b)
      }
    } catch {
      case e: IOException =>
        scribe.debug(s"closed: $name", e)
        isClosed = true
    }
  }
}
