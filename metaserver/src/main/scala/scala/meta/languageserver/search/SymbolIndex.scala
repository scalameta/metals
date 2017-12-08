package scala.meta.languageserver.search

import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.InMemory
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Sqlite
import scala.meta.languageserver.index.SymbolData
import langserver.core.Notifications
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Symbol

/**
 * A high-level wrapper around [[DocumentIndex]] and [[SymbolIndexer]].
 *
 * Can respond to high-level queries like "go to definition" and "find references".
 */
trait SymbolIndex {
  def findSymbol(path: AbsolutePath, line: Int, column: Int): Option[Symbol]
  def definitionData(symbol: Symbol): Option[SymbolData]
  def referencesData(symbol: Symbol): List[SymbolData]
  def indexDependencyClasspath(sourceJars: List[AbsolutePath]): Effects.IndexSourcesClasspath
  def indexDatabase(document: s.Database): Effects.IndexSemanticdb
}

object SymbolIndex {
  def apply(
      cwd: AbsolutePath,
      notifications: Notifications,
      buffers: Buffers,
      serverConfig: ServerConfig
  ): SymbolIndex = {
    serverConfig.indexingStrategy match {
      case InMemory =>
        val symbolIndexer = new InMemorySymbolIndexer()
        val documentIndex = new InMemoryDocumentIndex()
        new InMemorySymbolIndex(
          symbolIndexer,
          documentIndex,
          cwd,
          notifications,
          buffers,
          serverConfig
        )
      case Sqlite =>
        new SqliteSymbolIndex(
          cwd,
          notifications,
          buffers,
          serverConfig)
    }
  }

}
