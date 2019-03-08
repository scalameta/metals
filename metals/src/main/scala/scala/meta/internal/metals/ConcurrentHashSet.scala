package scala.meta.internal.metals

import java.util
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper to construct a concurrent mutable set.
 */
object ConcurrentHashSet {
  def empty[T]: util.Set[T] =
    Collections.newSetFromMap(
      new ConcurrentHashMap[T, java.lang.Boolean]()
    )
}
