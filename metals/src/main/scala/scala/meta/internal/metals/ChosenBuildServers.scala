package scala.meta.internal.metals

import java.sql.Connection
import JdbcEnrichments._
import java.sql.Timestamp

class ChosenBuildServers(conn: () => Connection, time: Time) {
  def selectedServer(md5: String): Option[String] = {
    conn()
      .query(
        "select selected_server from chosen_build_server where md5 = ?;"
      )(
        _.setString(1, md5)
      ) { rs =>
        rs.getString(1)
      }
      .headOption
  }

  def chooseServer(md5: String, server: String): Int = {
    conn().update(
      s"merge into chosen_build_server key(md5) values (?, ?, ?);"
    ) { stmt =>
      val timestamp = new Timestamp(time.millis())
      stmt.setString(1, md5)
      stmt.setString(2, server)
      stmt.setTimestamp(3, timestamp)
    }
  }
}
