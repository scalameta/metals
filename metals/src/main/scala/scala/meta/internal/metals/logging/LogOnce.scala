package scala.meta.internal.metals.logging

import java.util.concurrent.ConcurrentHashMap

object LogOnce {
  private val messages = new ConcurrentHashMap[LogOnceMessage, LogOnceMessage]()
  def info(message: LogOnceMessage): Unit = {
    val toLog = messages.compute(
      message,
      (key, old) =>
        if (old == null) key
        // Reuse an old message if it's already been logged
        else old,
    )
    if (toLog.isLogged.compareAndSet(false, true)) {
      scribe.info(toLog.message)
    }
  }
}
