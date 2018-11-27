package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.sql.Connection
import scala.meta.io.AbsolutePath
import JdbcEnrichments._

final class DependencySources(conn: Connection) {
  def setBuildTarget(
      dependencySource: AbsolutePath,
      buildTarget: BuildTargetIdentifier
  ): Int = {
    conn.update(
      "merge into dependency_source key(text_document_uri) values (?, ?);"
    ) { stmt =>
      stmt.setString(1, dependencySource.toURI.toString)
      stmt.setString(2, buildTarget.getUri)
    }
  }
  def getBuildTarget(
      dependencySource: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    conn.query(
      "select build_target_uri from dependency_source where text_document_uri = ?;"
    ) { stmt =>
      stmt.setString(1, dependencySource.toURI.toString)
    } { rs =>
      new BuildTargetIdentifier(rs.getString(1))
    }
  }.headOption
}
