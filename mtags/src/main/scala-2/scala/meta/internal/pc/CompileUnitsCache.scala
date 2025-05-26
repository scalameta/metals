package scala.meta.internal.pc

import java.net.URI

import scala.collection.mutable

class CompileUnitsCache(keepLastCount: Short) {
  private val lastOpened = new LastNElementsSet[URI](keepLastCount)
  private val lastModified = mutable.Set[URI]()

  def didFocus(uri: URI): Option[URI] = {
    lastOpened.add(uri).filterNot(lastModified.contains)
  }

  def didChange(uri: URI): Unit = {
    lastModified.add(uri)
  }

  def restart(): Unit = {
    lastModified.clear()
  }
}

/**
 * A set collection that keeps the last N elements.
 * @param keepLastCount the number of elements to keep
 */
class LastNElementsSet[T](keepLastCount: Short) {
  private val cache = new mutable.LinkedHashSet[T]()
  private val lock = new Object()

  /**
   * Add an element to the set.
   * @param element the element to add
   * @return the optional oldest element, that was removed from the set
   */
  def add(element: T): Option[T] = {
    lock.synchronized {
      if (cache.contains(element)) {
        // If element exists, remove it first to update its position
        cache.remove(element)
        cache.add(element)
        None
      } else if (cache.size >= keepLastCount) {
        val oldest = cache.head
        cache.remove(oldest)
        cache.add(element)
        Some(oldest)
      } else {
        cache.add(element)
        None
      }
    }
  }
}
