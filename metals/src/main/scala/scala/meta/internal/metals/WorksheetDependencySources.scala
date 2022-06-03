package scala.meta.internal.metals

import java.sql.Connection

import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

final class WorksheetDependencySources(conn: () => Connection) {
  def setWorksheet(
      dependencySource: AbsolutePath,
      worksheetSource: AbsolutePath,
  ): Int = {
    conn().update(
      "merge into worksheet_dependency_source key(text_document_uri) values (?, ?);"
    ) { stmt =>
      stmt.setString(1, dependencySource.toURI.toString)
      stmt.setString(2, worksheetSource.toURI.toString())
    }
  }
  def getWorksheet(
      dependencySource: AbsolutePath
  ): Option[AbsolutePath] = {
    conn().query(
      "select worksheet_uri from worksheet_dependency_source where text_document_uri = ?;"
    ) { stmt => stmt.setString(1, dependencySource.toURI.toString) } { rs =>
      rs.getString(1).toAbsolutePath
    }
  }.headOption

  def clearAll(): Unit = {
    val statement =
      conn().prepareStatement("truncate table worksheet_dependency_source")
    statement.execute()
  }
}
