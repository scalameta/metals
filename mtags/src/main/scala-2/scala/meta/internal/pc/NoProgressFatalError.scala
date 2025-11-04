package scala.meta.internal.pc

import scala.reflect.internal.FatalError

object NoProgressFatalError {
  def unapply(e: Throwable): Option[Throwable] = {
    e match {
      case FatalError(msg) if msg.contains("no progress in completing") =>
        Some(e)
      case _ => None
    }
  }
}
