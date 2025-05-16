package scala.meta.internal.metals

import java.sql.Connection

import scala.meta.internal.metals.JdbcEnrichments._

class ChosenMcpPort(conn: () => Connection) {

  def readPort(): Option[Int] = {
    conn()
      .query(
        "select * from chosen_mcp_port LIMIT 1;"
      )(_ => ()) { _.getInt("port") }
      .headOption
  }

  def setPort(port: Int): Int = synchronized {
    reset()
    conn().update {
      "insert into chosen_mcp_port values (?);"
    } { stmt => stmt.setInt(1, port) }
  }

  def reset(): Unit = {
    conn().update("delete from chosen_mcp_port;") { _ => () }
  }
}
