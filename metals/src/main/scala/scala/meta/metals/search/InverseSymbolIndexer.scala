package scala.meta.metals.search

import scala.collection.mutable
import scala.meta.metals.Uri
import scala.meta.metals.{index => i}
import scala.{meta => m}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.internal.semanticdb3.SymbolOccurrence

object InverseSymbolIndexer {

  /** Rebuilds a scala.meta.Database with only names filled out
   *
   * @param cwd the working directory to relativize file URIs in the symbol index.
   * @param documents store for looking up document text.
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
            documents.getDocument(uri).fold("")(_.text)
          ),
          "Scala",
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
        role: SymbolOccurrence.Role
    ): Unit = {
      if (symbol.startsWith("local")) return
      val doc = get(uri)
      val pos = doc.input.toPosition(range)
      val rs =
        m.ResolvedName(pos, m.Symbol(symbol), isDefinition = role.isDefinition)
      val newDoc = doc.copy(names = rs :: doc.names)
      db(doc.input.syntax) = newDoc
    }
    symbols.foreach { symbol =>
      symbol.definition.collect {
        case i.Position(Uri(uri), range) =>
          handleResolvedName(
            uri,
            symbol.symbol,
            range,
            SymbolOccurrence.Role.DEFINITION
          )
      }
      symbol.references.collect {
        case (uri, ranges) =>
          ranges.foreach { range =>
            handleResolvedName(
              uri,
              symbol.symbol,
              range,
              SymbolOccurrence.Role.REFERENCE
            )
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
