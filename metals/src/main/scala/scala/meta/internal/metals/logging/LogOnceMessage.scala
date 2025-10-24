package scala.meta.internal.metals.logging

import java.util.concurrent.atomic.AtomicBoolean

sealed abstract class LogOnceMessage(val message: String) {
  val isLogged = new AtomicBoolean(false)
}

case class TracingIsEnabled(head: String)
    extends LogOnceMessage(s"tracing is enabled: $head")
