package scala.meta.internal.metals

import java.nio.file.Paths
import java.sql.Connection
import java.sql.PreparedStatement

import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Wrapper around the fingerprints sql table.
 *
 * It's needed to save the last known state of the workspace files to
 * calculate differences between current state and semanticdb view of the file.
 *
 * We need to save it between restarts in case user reloaded a workspace with
 * errors.
 */
final class Fingerprints(conn: () => Connection) {

  def clearAll(): Unit = {
    val statement = conn().prepareStatement("truncate table fingerprints")
    statement.execute()
  }

  def save(all: Map[AbsolutePath, List[Fingerprint]]): AnyVal = if (
    all.nonEmpty
  ) {
    var symbolStmt: PreparedStatement = null
    try {
      symbolStmt = conn().prepareStatement(
        s"insert into fingerprints (path, text, md5) values (?, ?, ?)"
      )
      all.foreach { case (path, fingerprints) =>
        scribe.debug(s"Saving ${fingerprints.length} fingerprints for $path")
        fingerprints.foreach { fingerprint =>
          symbolStmt.setString(1, path.toString())
          symbolStmt.setString(2, fingerprint.text)
          symbolStmt.setString(3, fingerprint.md5)
          symbolStmt.addBatch()
        }
      }
      // Return number of rows inserted
      symbolStmt.executeBatch().sum
    } finally {
      if (symbolStmt != null) symbolStmt.close()
    }
  }

  def load(): Map[AbsolutePath, List[Fingerprint]] = {
    val results = conn()
      .query(
        "select path, text, md5 from fingerprints;"
      )(_ => ()) { rs =>
        val path = rs.getString(1)
        val text = rs.getString(2)
        val md5 = rs.getString(3)
        AbsolutePath(Paths.get(path)) -> Fingerprint(text, md5)
      }
      .groupBy { case (path, _) => path }
      .map { case (path, fingerprints) =>
        scribe.debug(s"Loading ${fingerprints.length} fingerprints for $path")
        path -> fingerprints.map { case (_, finger) => finger }
      }
      .toMap
    clearAll()
    results
  }

}
