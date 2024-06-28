package scala.meta.internal.metals

import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.zip.ZipError
import java.util.zip.ZipException

import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.JarTopLevels.getFileSystem
import scala.meta.internal.metals.JdbcEnrichments._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.OverriddenSymbol
import scala.meta.internal.mtags.ResolvedOverriddenSymbol
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.io.AbsolutePath

import org.h2.jdbc.JdbcBatchUpdateException
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException

/**
 * Handles caching of Jar Top Level Symbols in H2
 *
 * Wrapper around the indexed_jar and toplevel_symbol sql tables.
 */
final class JarTopLevels(conn: () => Connection)
    extends JarTypeHierarchy(conn) {

  /**
   * Retrieves top level Scala symbols of a jar from H2
   *
   * @param path absolute path of the jar
   * @return the top level Scala symbols in the jar
   */
  def getTopLevels(
      jar: AbsolutePath
  ): Option[List[(String, AbsolutePath)]] =
    try {
      val fs = getFileSystem(jar)
      val toplevels = List.newBuilder[(String, AbsolutePath)]
      conn()
        .query(
          """select ts.symbol, ts.path
            |from indexed_jar ij
            |left join toplevel_symbol ts
            |on ij.id=ts.jar
            |where ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(jar)) } { rs =>
          if (rs.getString(1) != null && rs.getString(2) != null) {
            val symbol = rs.getString(1)
            val path = AbsolutePath(fs.getPath(rs.getString(2)))
            toplevels += (symbol -> path)
          }
        }
        .headOption
        .map(_ => toplevels.result)
    } catch {
      case error @ (_: ZipError | _: ZipException) =>
        scribe.warn(s"corrupted jar $jar: $error")
        None
    }

  /**
   * Stores the top level symbols for the Jar
   *
   * @param path absolute path of the jar
   * @param toplevels toplevel symbols in the jar
   * @return the number of toplevel symbols inserted
   */
  def putJarIndexingInfo(
      path: AbsolutePath,
      toplevels: List[(String, AbsolutePath)],
      type_hierarchy: List[(AbsolutePath, String, OverriddenSymbol)],
  ): Int = {
    if (toplevels.isEmpty && type_hierarchy.isEmpty) 0
    else {
      // Add jar to H2
      val jar = addJar(path)
      jar
        .map(jar =>
          putToplevels(jar, toplevels) + putTypeHierarchyInfo(
            jar,
            type_hierarchy,
          )
        )
        .getOrElse(0)
    }
  }

  override def addTypeHierarchyInfo(
      path: AbsolutePath,
      type_hierarchy: List[(AbsolutePath, String, OverriddenSymbol)],
  ): Int = {
    var jarStmt: PreparedStatement = null
    val jar =
      try {
        val digest = getMD5Digest(path)
        jarStmt = conn().prepareStatement(
          s"update indexed_jar set type_hierarchy_indexed = true where (md5) = (?)"
        )
        jarStmt.setString(1, digest)
        jarStmt.executeUpdate()

        conn()
          .query(
            """select id
              |from indexed_jar
              |where md5=?""".stripMargin
          ) { _.setString(1, digest) } { _.getInt(1) }
          .head
      } finally {
        if (jarStmt != null) jarStmt.close()
      }
    putTypeHierarchyInfo(jar, type_hierarchy)
  }

  def putToplevels(
      jar: Int,
      toplevels: List[(String, AbsolutePath)],
  ): Int =
    if (toplevels.nonEmpty) {
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
      } catch {
        case e: JdbcBatchUpdateException =>
          scribe.warn(e)
          0
      } finally {
        if (symbolStmt != null) symbolStmt.close()
      }
    } else 0

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

  def clearAll(): Unit = {
    val statement1 = conn().prepareStatement("truncate table toplevel_symbol")
    statement1.execute()
    val statement2 =
      conn().prepareStatement("truncate table type_hierarchy")
    statement2.execute()
    val statement3 = conn().prepareStatement("delete from indexed_jar")
    statement3.execute()
  }

}

class JarTypeHierarchy(conn: () => Connection) {

  def getTypeHierarchy(
      jar: AbsolutePath
  ): Option[List[(AbsolutePath, String, OverriddenSymbol)]] =
    try {
      val fs = getFileSystem(jar)
      val toplevels = List.newBuilder[(AbsolutePath, String, OverriddenSymbol)]
      conn()
        .query(
          """select th.symbol, th.parent_name, th.path, th.is_resolved
            |from indexed_jar ij
            |left join type_hierarchy th
            |on ij.id=th.jar
            |where ij.type_hierarchy_indexed=true and ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(jar)) } { rs =>
          if (
            rs.getString(1) != null && rs
              .getString(2) != null && rs.getString(4) != null
          ) {
            val symbol = rs.getString(1)
            val parentName = rs.getString(2)
            val path = AbsolutePath(fs.getPath(rs.getString(3)))
            val isResolved = rs.getBoolean(4)
            val overridden =
              if (isResolved) ResolvedOverriddenSymbol(parentName)
              else UnresolvedOverriddenSymbol(parentName)
            toplevels += ((path, symbol, overridden))
          }
        }
        .headOption
        .map(_ => toplevels.result)
    } catch {
      case error @ (_: ZipError | _: ZipException) =>
        scribe.warn(s"corrupted jar $jar: $error")
        None
    }

  def addTypeHierarchyInfo(
      path: AbsolutePath,
      type_hierarchy: List[(AbsolutePath, String, OverriddenSymbol)],
  ): Int = {
    val jar = addJar(path)
    jar.map(putTypeHierarchyInfo(_, type_hierarchy)).getOrElse(0)
  }

  protected def addJar(path: AbsolutePath): Option[Int] = {
    var jarStmt: PreparedStatement = null
    try {
      jarStmt = conn().prepareStatement(
        s"insert into indexed_jar (md5, type_hierarchy_indexed) values (?, ?)",
        Statement.RETURN_GENERATED_KEYS,
      )
      jarStmt.setString(1, getMD5Digest(path))
      jarStmt.setBoolean(2, true)
      jarStmt.executeUpdate()
      val rs = jarStmt.getGeneratedKeys
      rs.next()
      Some(rs.getInt("id"))
    } catch {
      case e: JdbcSQLIntegrityConstraintViolationException =>
        // since we don't synchronize we might end up adding the same jar twice
        scribe.warn(e)
        None
    } finally {
      if (jarStmt != null) jarStmt.close()
    }
  }

  protected def putTypeHierarchyInfo(
      jar: Int,
      type_hierarchy: List[(AbsolutePath, String, OverriddenSymbol)],
  ): Int =
    if (type_hierarchy.nonEmpty) {
      // Add symbols for jar to H2
      var symbolStmt: PreparedStatement = null
      try {
        symbolStmt = conn().prepareStatement(
          s"insert into type_hierarchy (symbol, parent_name, path, jar, is_resolved) values (?, ?, ?, ?, ?)"
        )
        type_hierarchy.foreach { case (path, symbol, overridden) =>
          symbolStmt.setString(1, symbol)
          overridden match {
            case ResolvedOverriddenSymbol(name) =>
              symbolStmt.setString(2, name)
              symbolStmt.setInt(3, 0)
              symbolStmt.setBoolean(5, true)
            case UnresolvedOverriddenSymbol(name) =>
              symbolStmt.setString(2, name)
              symbolStmt.setBoolean(5, false)
          }
          symbolStmt.setString(3, path.toString())
          symbolStmt.setInt(4, jar)
          symbolStmt.addBatch()
        }
        // Return number of rows inserted
        symbolStmt.executeBatch().sum
      } catch {
        case e: JdbcBatchUpdateException =>
          scribe.warn(e)
          0
      } finally {
        if (symbolStmt != null) symbolStmt.close()
      }
    } else 0

  def getMD5Digest(path: AbsolutePath): String = {
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

object JarTopLevels {

  def getFileSystem(jar: AbsolutePath): FileSystem =
    jar.jarPath
      .map(jarPath =>
        PlatformFileIO.newFileSystem(
          jarPath.toURI,
          new java.util.HashMap[String, String](),
        )
      )
      .getOrElse(PlatformFileIO.newJarFileSystem(jar, create = false))
}
