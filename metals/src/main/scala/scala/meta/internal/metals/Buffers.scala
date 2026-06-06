package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.io.AbsolutePath

/**
 * Manages in-memory text contents of unsaved files in the editor.
 */
case class Buffers(
    map: TrieMap[AbsolutePath, String] = TrieMap.empty
) {
  private val versions = TrieMap.empty[AbsolutePath, Int]

  def open: Iterable[AbsolutePath] = map.keys
  def put(key: AbsolutePath, value: String): Unit = map.put(key, value)
  def put(key: AbsolutePath, value: String, version: Int): Unit = {
    map.put(key, value)
    versions.put(key, version)
  }
  def version(key: AbsolutePath): Option[Int] = versions.get(key)
  def get(key: AbsolutePath): Option[String] = map.get(key)
  def remove(key: AbsolutePath): Unit = {
    map.remove(key)
    versions.remove(key)
  }
  def contains(key: AbsolutePath): Boolean = map.contains(key)

  def tokenEditDistance(
      source: AbsolutePath,
      snapshot: String,
      scalaVersionSelector: ScalaVersionSelector,
  ): TokenEditDistance = {
    val bufferInput = source.toInputFromBuffers(this)
    val snapshotInput = Input.VirtualFile(bufferInput.path, snapshot)
    TokenEditDistance(snapshotInput, bufferInput, scalaVersionSelector)
      .getOrElse(
        TokenEditDistance.NoMatch
      )
  }

}
