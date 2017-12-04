package scala.meta.languageserver.search

import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.InMemory
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Sqlite
import scala.meta.languageserver.index.SymbolData
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.{types => l}
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Symbol

class SqliteSymbolIndex(
    cwd: AbsolutePath,
    notifications: Notifications,
    buffers: Buffers,
    serverConfig: ServerConfig,
) extends SymbolIndex with LazyLogging {
  def findSymbol(path: AbsolutePath, line: Int, column: Int): Option[Symbol] = {
    ???
  }

  def definitionData(symbol: Symbol): Option[SymbolData] = {
    ???
  }

  def referencesData(symbol: Symbol): List[SymbolData] = {
    ???
  }

  def indexDependencyClasspath(sourceJars: List[AbsolutePath]): Effects.IndexSourcesClasspath = {
    // TODO: Implement this.
    Effects.IndexSourcesClasspath
  }

  def indexDatabase(document: s.Database): Effects.IndexSemanticdb = {
    // TODO: Implement this.
    Effects.IndexSemanticdb
  }

  // TODO: Implement freshness checks.
}
