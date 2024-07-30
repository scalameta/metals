package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.Dialect
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels

class IdentifierIndex {
  val index: TrieMap[Path, IdentifierIndex.IndexEntry] = TrieMap.empty

  def addIdentifiers(
      file: AbsolutePath,
      id: BuildTargetIdentifier,
      set: Iterable[String],
  ): Unit = {
    val bloom = BloomFilter.create(
      Funnels.stringFunnel(StandardCharsets.UTF_8),
      Integer.valueOf(set.size * 2),
      0.01,
    )

    val entry = IdentifierIndex.IndexEntry(id, bloom)
    index(file.toNIO) = entry
    set.foreach(bloom.put)
  }

  def collectIdentifiers(
      text: String,
      dialect: Dialect,
  ): Iterable[String] = {
    val identifiers = Set.newBuilder[String]
    try {
      val scanner = new LegacyScanner(Input.String(text), dialect)
      scanner.foreach {
        case ident if ident.token == IDENTIFIER => identifiers += ident.strVal
        case _ =>
      }
    } catch {
      case NonFatal(_) =>
    }

    identifiers.result()
  }
}

object IdentifierIndex {
  case class IndexEntry(
      id: BuildTargetIdentifier,
      bloom: BloomFilter[CharSequence],
  )

  case class MaybeStaleIndexEntry(
      id: BuildTargetIdentifier,
      bloom: BloomFilter[CharSequence],
      isStale: Boolean,
  ) {
    def asStale: MaybeStaleIndexEntry =
      MaybeStaleIndexEntry(id, bloom, isStale = true)
  }
}
