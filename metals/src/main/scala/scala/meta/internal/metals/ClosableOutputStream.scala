package scala.meta.internal.metals

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

class ClosableOutputStream(underlying: OutputStream, name: String)
    extends FilterOutputStream(underlying) {
  private var isClosed = false

  override def flush(): Unit = {
    try {
      if (!isClosed) {
        super.flush()
      }
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
      // IOException is usually thrown when the stream is closed
      case e: IOException =>
        scribe.debug(s"closed: $name", e)
        isClosed = true
        throw e
    }
  }
}
