package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.sql.{Connection, PreparedStatement, Statement}

import scala.collection.concurrent.TrieMap
import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Handles caching of Jar Top Level Symbols in H2
 *
 * Wrapper around the indexed_jar and toplevel_symbol sql tables.
 */
final class JarTopLevels(conn: () => Connection) {

  /**
   * Retrieves top level Scala symbols of a jar from H2
   *
   * @param path absolute path of the jar
   * @param digest MD5 digest of the jar
   * @return the top level Scala symbols in the jar
   */
  def getTopLevels(
      path: AbsolutePath
  ): Option[TrieMap[String, AbsolutePath]] = {
    val fs = PlatformFileIO.newJarFileSystem(path, create = false)
    conn()
      .query(
        "select id from indexed_jar where path=? and last_modified=? and size=?"
      ) { q =>
        val attributes = getAttributes(path)
        q.setString(1, path.toString)
        q.setLong(2, attributes.lastModifiedTime().toMillis)
        q.setLong(3, attributes.size())
      } { _.getInt(1) }
      .map { jar =>
        val toplevels = TrieMap[String, AbsolutePath]()
        conn().query(
          "select symbol, path from toplevel_symbol where jar=?"
        )(q => q.setInt(1, jar)) { rs =>
          toplevels(rs.getString(1)) = AbsolutePath(fs.getPath(rs.getString(2)))
        }
        toplevels
      }
      .headOption
  }

  /**
   * Stores the top level symbols for the Jar in H2
   *
   * @param path absolute path of the jar
   * @param digest MD5 digest of the jar
   * @param toplevels toplevel symbols in the jar
   * @return the number of toplevel symbols inserted
   */
  def putTopLevels(
      path: AbsolutePath,
      toplevels: TrieMap[String, AbsolutePath]
  ): Int = {
    // Add jar to H2
    var jarStmt: PreparedStatement = null
    val jar = try {
      jarStmt = conn().prepareStatement(
        s"insert into indexed_jar (path, last_modified, size) values (?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS
      )
      val attributes = getAttributes(path)
      jarStmt.setString(1, path.toString)
      jarStmt.setLong(2, attributes.lastModifiedTime().toMillis)
      jarStmt.setLong(3, attributes.size())
      jarStmt.executeUpdate()
      val rs = jarStmt.getGeneratedKeys
      rs.next()
      rs.getInt("id")
    } finally {
      if (jarStmt != null) jarStmt.close()
    }

    // Add symbols for jar to H2
    // Turn off auto commit to speed up batch execution
    val ac = conn().getAutoCommit
    var symbolStmt: PreparedStatement = null
    try {
      conn().setAutoCommit(false)
      symbolStmt = conn().prepareStatement(
        s"insert into toplevel_symbol (symbol, path, jar) values (?, ?, ?)"
      )
      toplevels.foreach {
        case (symbol, source) =>
          symbolStmt.setString(1, symbol)
          symbolStmt.setString(2, source.toString)
          symbolStmt.setInt(3, jar)
          symbolStmt.addBatch()
      }
      // Return number of rows inserted
      symbolStmt.executeBatch().sum
    } finally {
      if (symbolStmt != null) symbolStmt.close()
      conn().setAutoCommit(ac)
    }
  }

  private def getAttributes(path: AbsolutePath) = {
    Files
      .getFileAttributeView(path.toNIO, classOf[BasicFileAttributeView])
      .readAttributes()
  }
}
