package scala.meta.internal.metals

import java.util.concurrent.CancellationException
import scala.util.control.ControlThrowable

/**
 * Wrapper around `CancellationException` with mixed in `ControlThrowable` to play nicely with NonFatal.
 */
class ControlCancellationException(cause: CancellationException)
    extends CancellationException(cause.getMessage)
    with ControlThrowable {
  override def getCause: Throwable = cause
}
