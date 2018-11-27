package scala.meta.internal.metals

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

object JdbcEnrichments {
  implicit class XtensionConnection(conn: Connection) {
    def update[T](
        query: String
    )(updateStatement: PreparedStatement => Unit): Int = {
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
    }
    def query[T](query: String)(
        updateStatement: PreparedStatement => Unit
    )(fn: ResultSet => T): List[T] = {
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
    }
  }
}
