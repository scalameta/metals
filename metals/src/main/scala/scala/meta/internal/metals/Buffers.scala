package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

/**
 * Manages in-memory text contents of unsaved files in the editor.
 */
case class Buffers(
    map: TrieMap[AbsolutePath, String] = TrieMap.empty
) {
  def open: Iterable[AbsolutePath] = map.keys
  def put(key: AbsolutePath, value: String): Unit = map.put(key, value)
  def get(key: AbsolutePath): Option[String] = map.get(key)
  def remove(key: AbsolutePath): Unit = map.remove(key)
  def contains(key: AbsolutePath): Boolean = map.contains(key)

  def tokenEditDistance(
      source: AbsolutePath,
      snapshot: String,
      trees: Trees,
  ): TokenEditDistance = {
    val bufferInput = source.toInputFromBuffers(this)
    val snapshotInput = Input.VirtualFile(bufferInput.path, snapshot)
    TokenEditDistance(snapshotInput, bufferInput, trees).getOrElse(
      TokenEditDistance.NoMatch
    )
  }

}
