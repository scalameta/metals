package scala.meta.internal.pc

import java.net.URI

import scala.collection.mutable
import scala.reflect.io.AbstractFile

class CompileUnitsCache(keepLastCount: Short) {
  private val lastCompiled = new LastNElementsSet[AbstractFile](keepLastCount)
  private val lastModified = mutable.Set[URI]()

  def didGetUnit(file: AbstractFile): Option[AbstractFile] = {
    lastCompiled
      .add(file)
      .filterNot(f => lastModified.contains(f.toURL.toURI()))

  }

  def didChange(uri: URI): Unit = {
    lastModified.add(uri)
  }
}

/**
 * A set collection that keeps the last N elements.
 * @param keepLastCount the number of elements to keep
 */
class LastNElementsSet[T](keepLastCount: Short) {
  private val cache = new mutable.LinkedHashSet[T]()
  private val lock = new Object()
  private var last: Option[T] = None

  /**
   * Add an element to the set.
   * @param element the element to add
   * @return the optional oldest element, that was removed from the set
   */
  def add(element: T): Option[T] = {
    lock.synchronized {
      // this may be a very often scenario, so we want to optimize it
      if (last.contains(element)) None
      else {
        last = Some(element)
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
}
