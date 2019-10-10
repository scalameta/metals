package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.meta.io.AbsolutePath
import MetalsEnrichments._
import scala.meta.inputs.Input

/**
 * Manages in-memory text contents of unsaved files in the editor.
 */
case class Buffers(map: TrieMap[AbsolutePath, String] = TrieMap.empty) {
  def open: Iterable[AbsolutePath] = map.keys
  def put(key: AbsolutePath, value: String): Unit = map.put(key, value)
  def get(key: AbsolutePath): Option[String] = map.get(key)
  def remove(key: AbsolutePath): Unit = map.remove(key)
  def contains(key: AbsolutePath): Boolean = map.contains(key)
}

object Buffers {
  def tokenEditDistance(
      source: AbsolutePath,
      snapshot: String,
      buffers: Buffers
  ): TokenEditDistance = {
    val bufferInput = source.toInputFromBuffers(buffers)
    val snapshotInput = Input.VirtualFile(bufferInput.path, snapshot)
    TokenEditDistance(snapshotInput, bufferInput)
  }
}
