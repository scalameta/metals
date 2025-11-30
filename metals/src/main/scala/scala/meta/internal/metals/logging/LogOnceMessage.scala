package scala.meta.internal.metals.logging

import java.util.concurrent.atomic.AtomicBoolean

abstract class LogOnceMessage(val message: String) {
  val isLogged = new AtomicBoolean(false)
}

case class TracingIsEnabled(head: String)
    extends LogOnceMessage(s"tracing is enabled: $head")

case class JvmRunEnvironmentNotSupported(connection: String)
    extends LogOnceMessage(
      s"${connection} does not support `buildTarget/jvmRunEnvironment`, unable to fetch run environment."
    )
