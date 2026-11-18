package scala.meta.internal.metals

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

import scala.util.control.NonFatal

object JdbcEnrichments {
  implicit class XtensionConnection(conn: Connection) {
    private val errorMessage =
      "You might need to remove the metals.mv.db file and restart Metals."

    private def handleError(e: Throwable): Unit =
      if (!H2ConnectionProvider.recoverIfBroken(conn, e))
        scribe.error(errorMessage, e)

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
        handleError(e)
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
        handleError(e)
        Nil
    }
  }
}
