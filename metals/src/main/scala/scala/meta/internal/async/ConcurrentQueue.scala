package scala.meta.internal.async
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable

object ConcurrentQueue {

  /**
   * Returns all elements in the queue and empties the queue */
  def pollAll[T](queue: ConcurrentLinkedQueue[T]): List[T] = {
    val buffer = mutable.ListBuffer.empty[T]
    var elem = queue.poll()
    while (elem != null) {
      buffer += elem
      elem = queue.poll()
    }
    buffer.toList
  }

}
