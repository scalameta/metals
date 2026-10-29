package scala.meta.internal.metals.mcp

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

/**
 * Input stream that invokes `onEof` once stdin reaches EOF or the read fails.
 *
 * The callback runs on a separate thread. The Model Context Protocol stdio
 * transport reads stdin on its own thread, and shutting the server down from
 * that thread can deadlock: `System.exit` runs shutdown hooks which may join
 * the thread still inside `read`.
 */
class EofAwareInputStream(input: InputStream, onEof: () => Unit)
    extends FilterInputStream(input) {

  private val signaled = new AtomicBoolean(false)

  override def read(): Int =
    try afterRead(in.read())
    catch {
      case e: IOException =>
        signalEof()
        throw e
    }

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    try afterRead(in.read(b, off, len))
    catch {
      case e: IOException =>
        signalEof()
        throw e
    }

  private def afterRead(n: Int): Int = {
    if (n < 0) signalEof()
    n
  }

  private def signalEof(): Unit =
    if (signaled.compareAndSet(false, true)) {
      val thread = new Thread(
        () =>
          try onEof()
          catch {
            case NonFatal(e) =>
              scribe.error("Failed to shut down after stdin closed", e)
          },
        "metals-mcp-stdin-closed",
      )
      thread.setDaemon(true)
      thread.start()
    }
}
