package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters._

/** Open collection of cancelables that should cancel together */
final class MutableCancelable extends Cancelable {
  private val toCancel = new ConcurrentLinkedQueue[Cancelable]()
  def add(cancelable: Cancelable): this.type = {
    toCancel.add(cancelable)
    this
  }
  def addAll(cancelables: Iterable[Cancelable]): this.type = {
    cancelables.foreach { cancelable =>
      toCancel.add(cancelable)
    }
    this
  }
  override def cancel(): Unit = Cancelable.cancelAll(toCancel.asScala)
}
