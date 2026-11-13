package scala.meta.internal.metals.mbt.importer

import java.sql.Connection
import java.sql.Timestamp

import scala.meta.internal.metals.JdbcEnrichments.*
import scala.meta.internal.metals.Time
import scala.meta.io.AbsolutePath

final class BazelMbtNamespaceModes(conn: () => Connection, time: Time) {

  def selectedMode(projectRoot: AbsolutePath): Option[String] = {
    conn()
      .query(
        "select namespace_mode from bazel_mbt_namespace_mode where project_root = ?;"
      )(
        _.setString(1, projectRoot.toString())
      ) { rs => rs.getString(1) }
      .headOption
  }

  def chooseMode(projectRoot: AbsolutePath, mode: String): Int = {
    conn().update(
      "merge into bazel_mbt_namespace_mode key(project_root) values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.currentMillis())
      stmt.setString(1, projectRoot.toString())
      stmt.setString(2, mode)
      stmt.setTimestamp(3, timestamp)
    }
  }
}
