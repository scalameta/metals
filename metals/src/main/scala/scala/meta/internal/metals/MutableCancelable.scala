package scala.meta.internal.metals

import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue

import scala.meta.internal.async.ConcurrentQueue

/**
 * Open collection of cancelables that should cancel together
 */
final class MutableCancelable extends Cancelable {
  private val toCancel = new ConcurrentLinkedQueue[Cancelable]()
  def registerCloseable[T <: Closeable](closeable: T): T = {
    toCancel.add(Cancelable.fromCloseable(closeable))
    closeable
  }
  def register[T <: Cancelable](cancelable: T): T = {
    toCancel.add(cancelable)
    cancelable
  }
  def add(cancelable: Cancelable): this.type = {
    toCancel.add(cancelable)
    this
  }
  def addAll(cancelables: Iterable[Cancelable]): this.type = {
    cancelables.foreach { cancelable => toCancel.add(cancelable) }
    this
  }
  def remove(cancelable: Cancelable): this.type = {
    toCancel.remove(cancelable)
    this
  }
  override def cancel(): Unit = {
    Cancelable.cancelAll(ConcurrentQueue.pollAll(toCancel))
  }
}
