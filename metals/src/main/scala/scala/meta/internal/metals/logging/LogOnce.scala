package scala.meta.internal.metals.logging

import java.util.concurrent.ConcurrentHashMap

object LogOnce {
  private val messages = new ConcurrentHashMap[LogOnceMessage, LogOnceMessage]()
  def info(message: LogOnceMessage)(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: scribe.mdc.MDC,
  ): Unit = {
    log(scribe.Level.Info, message)
  }

  def warn(message: LogOnceMessage)(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: scribe.mdc.MDC,
  ): Unit = {
    log(scribe.Level.Warn, message)
  }

  def error(message: LogOnceMessage)(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: scribe.mdc.MDC,
  ): Unit = {
    log(scribe.Level.Error, message)
  }

  def log(level: scribe.Level, message: LogOnceMessage)(implicit
      pkg: sourcecode.Pkg,
      fileName: sourcecode.FileName,
      name: sourcecode.Name,
      line: sourcecode.Line,
      mdc: scribe.mdc.MDC,
  ): Unit = {
    val toLog = messages.compute(
      message,
      (key, old) =>
        if (old == null) key
        // Reuse an old message if it's already been logged
        else old,
    )
    if (toLog.isLogged.compareAndSet(false, true)) {
      scribe.log(level, mdc, message.message)
    }
  }
}
