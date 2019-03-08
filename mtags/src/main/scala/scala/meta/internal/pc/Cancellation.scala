package scala.meta.internal.pc

import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

object Cancellation {
  def unapply(e: Throwable): Boolean = e match {
    case _: InterruptedException | _: ClosedByInterruptException |
        _: CancellationException =>
      true
    case _ =>
      false
  }
}
