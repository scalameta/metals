package scala.meta.internal.metals.debug

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.debug.DebuggeeOutput.PrefixPattern

final class DebuggeeOutput {
  private val output = new StringBuilder
  @volatile private var listeners = mutable.Buffer.empty[PrefixPattern]

  def apply(): String = synchronized(output.toString())

  def append(message: String): Unit =
    synchronized {
      output.append(message)

      val remaining = listeners.filterNot(_.matches(output.toString()))
      listeners = remaining
    }

  def awaitPrefix(prefix: String): Future[Unit] =
    synchronized {
      if (output.startsWith(prefix)) {
        Future.unit
      } else {
        val promise = Promise[Unit]()
        listeners.append(new PrefixPattern(prefix, promise))
        promise.future
      }
    }
}

object DebuggeeOutput {
  protected final class PrefixPattern(prefix: String, promise: Promise[Unit]) {
    def matches(output: String): Boolean = {
      if (output.startsWith(prefix)) {
        promise.success(())
        true
      } else {
        false
      }
    }

    def foo(): Unit = {
      promise.success(())
    }
  }
}
