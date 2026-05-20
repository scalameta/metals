package scala.meta.internal.metals

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

import scala.util.control.NonFatal

object JdbcEnrichments {
  implicit class XtensionConnection(conn: Connection) {
    private val errorMessage =
      "You might need to remove .metals/metals.mv.db file and restart Metals."
    def update[T](
        query: String
    )(updateStatement: PreparedStatement => Unit): Int = try {
      if (conn.isClosed) {
        scribe.warn(s"sql closed: $query")
        0
      } else {
        var stmt: PreparedStatement = null
        try {
          stmt = conn.prepareStatement(query)
          updateStatement(stmt)
          stmt.executeUpdate()
        } finally {
          if (stmt != null) stmt.close()
        }
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(
          errorMessage,
          e,
        )
        0
    }

    def query[T](query: String)(
        updateStatement: PreparedStatement => Unit
    )(fn: ResultSet => T): List[T] = try {
      if (conn.isClosed) {
        scribe.warn(s"sql closed: $query")
        Nil
      } else {
        var stmt: PreparedStatement = null
        try {
          stmt = conn.prepareStatement(query)
          updateStatement(stmt)
          val rs = stmt.executeQuery()
          val buf = List.newBuilder[T]
          while (rs.next()) {
            buf += fn(rs)
          }
          buf.result()
        } finally {
          if (stmt != null) stmt.close()
        }
      }
    } catch {
      case NonFatal(e) =>
        scribe.error(
          errorMessage,
          e,
        )
        Nil
    }
  }
}
