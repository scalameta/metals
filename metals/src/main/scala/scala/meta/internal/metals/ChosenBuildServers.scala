package scala.meta.internal.metals

import java.sql.Connection
import java.sql.Timestamp

import scala.meta.internal.metals.JdbcEnrichments._

class ChosenBuildServers(conn: () => Connection, time: Time) {
  final val explicit = "EXPLICIT"

  def selectedServer(): Option[String] = {
    conn()
      .query(
        "select selected_server from chosen_build_server where md5 = ?;"
      )(
        _.setString(1, explicit)
      ) { rs => rs.getString(1) }
      .headOption
      .orElse {
        // For compatibility, we removed the md5 requirement
        conn()
          .query(
            "select selected_server from chosen_build_server;"
          )(_ => ()) { rs => rs.getString(1) }
          .headOption
      }
  }

  def reset(): Unit =
    conn().update("delete from chosen_build_server where md5 = ?;")(
      _.setString(1, explicit)
    )

  def chooseServer(server: String): Int = {
    conn().update(
      s"merge into chosen_build_server key(md5) values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.currentMillis())
      stmt.setString(1, explicit)
      stmt.setString(2, server)
      stmt.setTimestamp(3, timestamp)
    }
  }
}
