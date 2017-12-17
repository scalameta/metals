package scala.meta.languageserver.search

import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.index.SymbolData
import scala.meta.languageserver.Uri
import langserver.core.Notifications
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Symbol

trait SymbolIndex {

  /** Returns a symbol at the given location */
  def findSymbol(path: Uri, line: Int, column: Int): Option[Symbol]

  /** Returns symbol definition data from the index taking into account relevant alternatives */
  def definitionData(symbol: Symbol): Option[SymbolData]

  /** Returns symbol references data from the index taking into account relevant alternatives */
  def referencesData(symbol: Symbol): List[SymbolData]

  def indexDependencyClasspath(
      sourceJars: List[AbsolutePath]
  ): Effects.IndexSourcesClasspath

  /** Register this Database to symbol indexer. */
  def indexDatabase(document: s.Database): Effects.IndexSemanticdb
}

object SymbolIndex {
  def apply(
      cwd: AbsolutePath,
      notifications: Notifications,
      buffers: Buffers,
      serverConfig: ServerConfig
  ): SymbolIndex = {
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
  }

}
