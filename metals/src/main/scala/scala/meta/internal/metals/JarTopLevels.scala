package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.sql.{Connection, PreparedStatement, Statement}
import scala.collection.concurrent.TrieMap
import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.MD5
import java.util.zip.ZipError

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
   * @return the top level Scala symbols in the jar
   */
  def getTopLevels(
      path: AbsolutePath
  ): Option[TrieMap[String, AbsolutePath]] =
    try {
      val fs = PlatformFileIO.newJarFileSystem(path, create = false)
      val toplevels = TrieMap[String, AbsolutePath]()
      conn()
        .query(
          """select ts.symbol, ts.path
            |from indexed_jar ij
            |left join toplevel_symbol ts
            |on ij.id=ts.jar
            |where ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(path)) } { rs =>
          if (rs.getString(1) != null && rs.getString(2) != null)
            toplevels(rs.getString(1)) =
              AbsolutePath(fs.getPath(rs.getString(2)))
        }
        .headOption
        .map(_ => toplevels)
    } catch {
      case _: ZipError =>
        None
    }

  /**
   * Stores the top level symbols for the Jar
   *
   * @param path absolute path of the jar
   * @param toplevels toplevel symbols in the jar
   * @return the number of toplevel symbols inserted
   */
  def putTopLevels(
      path: AbsolutePath,
      toplevels: TrieMap[String, AbsolutePath]
  ): Int = {
    // Add jar to H2
    var jarStmt: PreparedStatement = null
    val jar =
      try {
        jarStmt = conn().prepareStatement(
          s"insert into indexed_jar (md5) values (?)",
          Statement.RETURN_GENERATED_KEYS
        )
        jarStmt.setString(1, getMD5Digest(path))
        jarStmt.executeUpdate()
        val rs = jarStmt.getGeneratedKeys
        rs.next()
        rs.getInt("id")
      } finally {
        if (jarStmt != null) jarStmt.close()
      }

    // Add symbols for jar to H2
    var symbolStmt: PreparedStatement = null
    try {
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
    }
  }

  /**
   * Delete the jars that are not used and their top level symbols
   *
   * @param usedPaths paths of the used Jars
   * @return number of jars deleted
   */
  def deleteNotUsedTopLevels(usedPaths: Array[AbsolutePath]): Int = {
    val md5s = usedPaths.map(getMD5Digest).map("'" + _ + "'").mkString(",")
    conn().update {
      s"delete from indexed_jar where md5 not in ($md5s)"
    } { _ => () }
  }

  private def getMD5Digest(path: AbsolutePath) = {
    val attributes = Files
      .getFileAttributeView(path.toNIO, classOf[BasicFileAttributeView])
      .readAttributes()
    MD5.compute(
      path.toString + ":" + attributes
        .lastModifiedTime()
        .toMillis + ":" + attributes.size()
    )
  }
}
