package scala.meta.internal.mtags

import java.util.concurrent.ConcurrentHashMap

import scala.collection.concurrent.TrieMap

/**
 * This class is a wrapper around TrieMap that provides atomic updateWith
 */
final class AtomicTrieMap[K, V] {
  private val trieMap = new TrieMap[K, V]()
  private val concurrentMap = new ConcurrentHashMap[K, V]

  def get(key: K): Option[V] = trieMap.get(key)

  def contains(key: K): Boolean = trieMap.contains(key)

  def updateWith(key: K)(remappingFunc: Option[V] => Option[V]): Unit = {
    val computeFunction = new java.util.function.BiFunction[K, V, V] {
      override def apply(k: K, v: V): V = {
        trieMap.get(key) match {
          case Some(value) =>
            remappingFunc(Some(value)) match {
              case Some(newValue) =>
                trieMap.update(key, newValue)
              case None =>
                trieMap.remove(key)
            }
          case None =>
            remappingFunc(None).foreach(trieMap.update(key, _))
        }
        null.asInstanceOf[V]
      }
    }
    concurrentMap.compute(key, computeFunction)
  }
}

object AtomicTrieMap {
  def empty[K, V]: AtomicTrieMap[K, V] = new AtomicTrieMap[K, V]
}
