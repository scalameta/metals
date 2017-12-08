package scala.meta.languageserver.search

import java.sql._
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.InMemory
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Sqlite
import scala.meta.languageserver.{index => i}
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

  private val findSymbolSql =
    """|select s.symbol
       |from document as d, name as n, symbol as s
       |where d.filename == ?
       |and n.document == d.id
       |and n.start_line <= ?
       |and n.start_character <= ?
       |and n.end_line >= ?
       |and n.end_character >= ?
       |and s.id=n.symbol""".stripMargin
  private val findSymbolStmt = conn.map(_.prepareStatement(findSymbolSql))

  def findSymbol(path: AbsolutePath, line: Int, column: Int): Option[Symbol] = {
    findSymbolStmt match {
      case Some(findSymbolStmt) =>
        findSymbolStmt.setString(1, path.toRelative(cwd).toString)
        findSymbolStmt.setInt(2, line)
        findSymbolStmt.setInt(3, column)
        findSymbolStmt.setInt(4, line)
        findSymbolStmt.setInt(5, column)
        val findSymbolRs = findSymbolStmt.executeQuery()
        try {
          while (findSymbolRs.next()) {
            val s_symbol = findSymbolRs.getString(1)
            return Some(Symbol(s_symbol))
          }
          None
        } finally {
          findSymbolRs.close()
        }
      case _ =>
        None
    }
  }

  private val symbolIdSql = "select s.id from symbol as s where s.symbol == ?"
  private val definitionDataSql =
    """|select d.filename, n.start_line, n.start_character, n.end_line, n.end_character
       |from name as n, document as d
       |where n.symbol == ?
       |and n.is_definition == 1
       |and d.id == n.document""".stripMargin
  private val symbolIdStmt = conn.map(_.prepareStatement(symbolIdSql))
  private val definitionDataStmt = conn.map(_.prepareStatement(definitionDataSql))

  def definitionData(symbol: Symbol): Option[SymbolData] = {
    (symbolIdStmt, definitionDataStmt) match {
      case (Some(symbolIdStmt), Some(definitionDataStmt)) =>
        // TODO: Take into account symbol.definitionAlternative.
        symbolIdStmt.setString(1, symbol.toString)
        val symbolIdRs = symbolIdStmt.executeQuery()
        try {
          while (symbolIdRs.next()) {
            val symbolId = symbolIdRs.getInt(1)
            definitionDataStmt.setInt(1, symbolId)
            val definitionDataRs = definitionDataStmt.executeQuery()
            try {
              while (definitionDataRs.next()) {
                val uri = s"file://${cwd.resolve(definitionDataRs.getString(1))}"
                val startLine = definitionDataRs.getInt(2)
                val startCharacter = definitionDataRs.getInt(3)
                val endLine = definitionDataRs.getInt(4)
                val endCharacter = definitionDataRs.getInt(5)
                val range = i.Range(startLine, startCharacter, endLine, endCharacter)
                val definition = i.Position(uri, Some(range))
                return Some(SymbolData(definition = Some(definition)))
              }
              return Some(SymbolData(definition = None))
            } finally {
              definitionDataRs.close()
            }
          }
          None
        } finally {
          symbolIdRs.close()
        }
      case _ =>
        None
    }
  }

  private val referencesDataSql =
    """|select d.filename, n.start_line, n.start_character, n.end_line, n.end_character, n.is_definition
       |from name as n, document as d
       |where n.symbol == ?
       |and d.id == n.document
       |and d.filename is not null""".stripMargin
  private val referencesDataStmt = conn.map(_.prepareStatement(referencesDataSql))

  def referencesData(symbol: Symbol): List[SymbolData] = {
    (symbolIdStmt, referencesDataStmt) match {
      case (Some(symbolIdStmt), Some(referencesDataStmt)) =>
        // TODO: Take into account symbol.referenceAlternatives.
        symbolIdStmt.setString(1, symbol.toString)
        val symbolIdRs = symbolIdStmt.executeQuery()
        try {
          while (symbolIdRs.next()) {
            val symbolId = symbolIdRs.getInt(1)
            referencesDataStmt.setInt(1, symbolId)
            val referencesDataRs = referencesDataStmt.executeQuery()
            try {
              var definition: Option[i.Position] = None
              val referencesBuf = List.newBuilder[i.Position]
              while (referencesDataRs.next()) {
                val uri = s"file://${cwd.resolve(referencesDataRs.getString(1))}"
                val startLine = referencesDataRs.getInt(2)
                val startCharacter = referencesDataRs.getInt(3)
                val endLine = referencesDataRs.getInt(4)
                val endCharacter = referencesDataRs.getInt(5)
                val range = i.Range(startLine, startCharacter, endLine, endCharacter)
                val position = i.Position(uri, Some(range))
                if (referencesDataRs.getBoolean(6)) definition = Some(position)
                else referencesBuf += position
              }
              val references = referencesBuf.result.groupBy(_.uri).mapValues(rs => i.Ranges(rs.map(_.range.get)))
              return List(SymbolData(definition = definition, references = references))
            } finally {
              referencesDataRs.close()
            }
          }
          Nil
        } finally {
          symbolIdRs.close()
        }
      case _ =>
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
