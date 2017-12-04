package scala.meta.languageserver.search

import java.sql._
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
  private val conn: Option[Connection] = {
    val connPath = cwd.resolve(".metaserver").resolve("semanticdb.sqlite")
    val connString = s"jdbc:sqlite:$connPath"
    try {
      val conn = DriverManager.getConnection(connString)
      logger.info(s"Successfully initialized connection to $connString")
      Some(conn)
    } catch {
      case ex: Throwable =>
        logger.error(s"Failed to initialize connection to $connString", ex)
        None
    }
  }

  def findSymbol(path: AbsolutePath, line: Int, column: Int): Option[Symbol] = {
    conn match {
      case Some(conn) =>
        ???
      case None =>
        None
    }
  }

  def definitionData(symbol: Symbol): Option[SymbolData] = {
    conn match {
      case Some(conn) =>
        ???
      case None =>
        None
    }
  }

  def referencesData(symbol: Symbol): List[SymbolData] = {
    conn match {
      case Some(conn) =>
        ???
      case None =>
        Nil
    }
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
