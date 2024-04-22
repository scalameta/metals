package scala.meta.internal.metals.debug.server

import ch.epfl.scala.debugadapter.Logger

class DebugLogger extends Logger {

  override def debug(msg: => String): Unit = scribe.debug(msg)

  override def info(msg: => String): Unit = scribe.info(msg)

  override def warn(msg: => String): Unit = scribe.warn(msg)

  override def error(msg: => String): Unit = scribe.error(msg)

  override def trace(t: => Throwable): Unit =
    scribe.trace(s"$t: ${t.getStackTrace().mkString("\n\t")}")

}
