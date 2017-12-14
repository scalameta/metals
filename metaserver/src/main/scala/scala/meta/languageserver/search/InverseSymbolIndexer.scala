package scala.meta.languageserver.search

import java.net.URI
import java.nio.file.Paths
import scala.collection.mutable
import scala.meta.languageserver.Uri
import scala.meta.languageserver.{index => i}
import scala.{meta => m}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._

object InverseSymbolIndexer {

  /** Rebuilds a scala.meta.Database with only names filled out
   *
   * @param cwd the working directory to relativize file URIs in the symbol index.
   * @param documents store for looking up document contents.
   * @param symbols symbol index, from [[SymbolIndexer.allSymbols]]
   */
  def reconstructDatabase(
      cwd: AbsolutePath,
      documents: DocumentIndex,
      symbols: Traversable[i.SymbolData]
  ): m.Database = {
    // Reconstruct an m.Database from the symbol index and asserts that the
    // reconstructed database is identical to the original semanticdbs that
    // built the symbol index.
    // TODO(olafur) handle local symbols when we stop indexing them.
    val db = mutable.Map.empty[String, m.Document]
    def get(uri: Uri) = {
      val key = if (uri.isFile) {
        cwd.toNIO.relativize(uri.toPath).toString
      } else uri.value
      db.getOrElseUpdate(
        key,
        m.Document(
          m.Input.VirtualFile(
            key,
            documents.getDocument(uri).fold("")(_.contents)
          ),
          "Scala212",
          Nil,
          Nil,
          Nil,
          Nil
        )
      )
    }
    def handleResolvedName(
        uri: Uri,
        symbol: String,
        range: i.Range,
        definition: Boolean
    ): Unit = {
      val doc = get(uri)
      val pos = doc.input.toPosition(range)
      val rs =
        m.ResolvedName(pos, m.Symbol(symbol), isDefinition = definition)
      val newDoc = doc.copy(names = rs :: doc.names)
      db(doc.input.syntax) = newDoc
    }
    symbols.foreach { symbol =>
      symbol.definition.collect {
        case i.Position(Uri(uri), Some(range)) =>
          handleResolvedName(uri, symbol.symbol, range, definition = true)
      }
      symbol.references.collect {
        case (Uri(uri), ranges) =>
          ranges.ranges.foreach { range =>
            handleResolvedName(uri, symbol.symbol, range, definition = false)
          }
      }
    }
    val reconstructedDatabase = m.Database(
      db.values.iterator
        .filter(!_.input.syntax.startsWith("jar:"))
        .filter(_.input.chars.nonEmpty)
        .toArray
        .sortBy(_.input.syntax)
    )
    reconstructedDatabase
  }
}
