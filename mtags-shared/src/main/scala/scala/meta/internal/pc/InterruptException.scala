package scala.meta.internal.pc

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

/**
 * Extractor for exceptions that are caused by `Thread.interrupt()`.
 */
object InterruptException {
  def unapply(e: Throwable): Boolean =
    e match {
      case _: InterruptedException | _: ClosedByInterruptException |
          _: CancellationException =>
        true
      case _ =>
        if (e.getCause() != null) {
          unapply(e.getCause())
        } else {
          false
        }
    }
}
