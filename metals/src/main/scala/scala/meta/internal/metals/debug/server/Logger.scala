package scala.meta.internal.metals.debug.server

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import ch.epfl.scala.debugadapter.DebuggeeListener

class Logger(listener: DebuggeeListener) {
  private val initialized = new AtomicBoolean(false)
  private final val JDINotificationPrefix =
    "Listening for transport dt_socket at address: "

  def logError(errorMessage: String): Unit = {
    listener.err(errorMessage)
    scribe.error(errorMessage)
  }

  def logOutput(msg: String): Unit = {
    if (msg.startsWith(JDINotificationPrefix)) {
      if (initialized.compareAndSet(false, true)) {
        val port = Integer.parseInt(msg.drop(JDINotificationPrefix.length))
        val address = new InetSocketAddress("127.0.0.1", port)
        listener.onListening(address)
      }
    } else listener.out(msg)
  }
}
