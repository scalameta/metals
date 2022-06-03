package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.zip.ZipError
import java.util.zip.ZipException

import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.mtags.MD5
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
   * @return the top level Scala symbols in the jar
   */
  def getTopLevels(
      path: AbsolutePath
  ): Option[List[(String, AbsolutePath)]] =
    try {
      val fs = path.jarPath
        .map(jarPath =>
          PlatformFileIO.newFileSystem(
            jarPath.toURI,
            new java.util.HashMap[String, String](),
          )
        )
        .getOrElse(PlatformFileIO.newJarFileSystem(path, create = false))
      val toplevels = List.newBuilder[(String, AbsolutePath)]
      conn()
        .query(
          """select ts.symbol, ts.path
            |from indexed_jar ij
            |left join toplevel_symbol ts
            |on ij.id=ts.jar
            |where ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(path)) } { rs =>
          if (rs.getString(1) != null && rs.getString(2) != null) {
            val symbol = rs.getString(1)
            val path = AbsolutePath(fs.getPath(rs.getString(2)))
            toplevels += (symbol -> path)
          }
        }
        .headOption
        .map(_ => toplevels.result)
    } catch {
      case _: ZipError | _: ZipException =>
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
      toplevels: List[(String, AbsolutePath)],
  ): Int = {
    if (toplevels.isEmpty) 0
    else {
      // Add jar to H2
      var jarStmt: PreparedStatement = null
      val jar =
        try {
          jarStmt = conn().prepareStatement(
            s"insert into indexed_jar (md5) values (?)",
            Statement.RETURN_GENERATED_KEYS,
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
        toplevels.foreach { case (symbol, source) =>
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

  def clearAll(): Unit = {
    val statement1 = conn().prepareStatement("truncate table toplevel_symbol")
    statement1.execute()
    val statement2 = conn().prepareStatement("delete from indexed_jar")
    statement2.execute()
  }
}
