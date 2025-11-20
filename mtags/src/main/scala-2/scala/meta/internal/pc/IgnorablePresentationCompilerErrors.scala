package scala.meta.internal.pc

import scala.reflect.internal.FatalError

/**
 * Presentation compiler errors that are safe to ignore, even though they are generally
 * fatal and very visible. Their impact is much more limited in a presentation compiler context
 * than in a context where code needs to be generated (full blown compiler).
 */
object IgnorablePresentationCompilerErrors {
  def unapply(e: Throwable): Option[Throwable] = {
    e match {
      case FatalError(msg) if msg.contains("no progress in completing") =>
        Some(e)
      case e: AssertionError
          if e.getMessage.contains(
            "Inconsistent class/module symbol pair for"
          ) =>
        Some(e)
      case _ => None
    }
  }
}
