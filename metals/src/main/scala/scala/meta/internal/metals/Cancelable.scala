package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/**
 * A copy of `java.io.Closeable` that is not auto closeable.
 *
 * Gives us fine-grained control over when resources are cleaned up.
 */
trait Cancelable {
  def cancel(): Unit
}

object Cancelable {
  def apply(fn: () => Unit): Cancelable =
    new Cancelable {
      override def cancel(): Unit = fn()
    }
  val empty: Cancelable = Cancelable(() => ())

  def cancelEach[T](iterable: Iterable[T])(fn: T => Unit): Unit = {
    cancelAll(iterable.map(elem => Cancelable(() => fn(elem))))
  }

  def cancelAll(iterable: Iterable[Cancelable]): Unit = {
    var errors = ListBuffer.empty[Throwable]
    iterable.foreach { cancelable =>
      try cancelable.cancel()
      catch { case ex if NonFatal(ex) => errors += ex }
    }
    errors.toList match {
      case head :: tail =>
        tail.foreach { e =>
          if (e ne head) {
            head.addSuppressed(e)
          }
        }
        throw head
      case _ =>
    }
  }
}
