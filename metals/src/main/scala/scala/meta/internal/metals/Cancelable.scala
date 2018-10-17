package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

trait Cancelable {
  def cancel(): Unit
}
object Cancelable {
  def apply(fn: () => Unit): Cancelable = new Cancelable {
    override def cancel(): Unit = fn()
  }
  val empty: Cancelable = Cancelable(() => ())
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

class OpenCancelable extends Cancelable {
  private val toCancel = ListBuffer.empty[Cancelable]
  def add(cancelable: Cancelable): Unit = toCancel += cancelable
  def addAll(cancelable: Iterable[Cancelable]): Unit = toCancel ++= cancelable
  override def cancel(): Unit = Cancelable.cancelAll(toCancel)
}
