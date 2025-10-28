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
import scala.meta.internal.mtags.ImplicitClassMember
import scala.meta.internal.mtags.OverriddenSymbol
import scala.meta.internal.mtags.ResolvedOverriddenSymbol
import scala.meta.internal.mtags.ToplevelMember
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.io.AbsolutePath

import org.h2.jdbc.JdbcBatchUpdateException
import org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException

/**
 * Handles caching of Jar Top Level Symbols in H2
 *
 * Wrapper around the indexed_jar and toplevel_symbol sql tables.
 */
final class JarTopLevels(conn: () => Connection) extends JarIndexingInfo(conn) {

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
        .map(_ => toplevels.result())
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
      toplevelMembers: Map[AbsolutePath, List[ToplevelMember]] = Map.empty,
      implicitClassMembers: Map[AbsolutePath, List[ImplicitClassMember]] =
        Map.empty,
  ): Int = {
    if (
      toplevels.isEmpty && type_hierarchy.isEmpty && toplevelMembers.isEmpty && implicitClassMembers.isEmpty
    )
      0
    else {
      // Add jar to H2
      addOrUpdateJar(path, "type_hierarchy_indexed")
      addOrUpdateJar(path, "toplevel_members_indexed")
      val jar = addOrUpdateJar(path, "implicit_class_members_indexed")
      jar
        .map(jar =>
          putToplevels(jar, toplevels) + putTypeHierarchyInfo(
            jar,
            type_hierarchy,
          ) + putToplevelMembersInfo(
            jar,
            toplevelMembers,
          ) + putImplicitClassMembersInfo(jar, implicitClassMembers)
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

class JarIndexingInfo(conn: () => Connection) {

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
        .map(_ => toplevels.result())
    } catch {
      case error @ (_: ZipError | _: ZipException) =>
        scribe.warn(s"corrupted jar $jar: $error")
        None
    }

  def addTypeHierarchyInfo(
      path: AbsolutePath,
      type_hierarchy: List[(AbsolutePath, String, OverriddenSymbol)],
  ): Int = {
    val jar = addOrUpdateJar(path, "type_hierarchy_indexed")
    jar.map(putTypeHierarchyInfo(_, type_hierarchy)).getOrElse(0)
  }

  protected def addOrUpdateJar(
      path: AbsolutePath,
      indexedField: String,
  ): Option[Int] = {
    val digest = getMD5Digest(path)

    // First, try to get existing jar
    val existingJar = conn()
      .query("select id from indexed_jar where md5=?") { stmt =>
        stmt.setString(1, digest)
      } { rs =>
        rs.getInt(1)
      }
      .headOption

    existingJar match {
      case Some(jarId) =>
        // Update existing jar
        var updateStmt: PreparedStatement = null
        try {
          updateStmt = conn().prepareStatement(
            s"update indexed_jar set $indexedField = true where id = ?"
          )
          updateStmt.setInt(1, jarId)
          updateStmt.executeUpdate()
          Some(jarId)
        } finally {
          if (updateStmt != null) updateStmt.close()
        }
      case None =>
        // Insert new jar
        var insertStmt: PreparedStatement = null
        try {
          insertStmt = conn().prepareStatement(
            s"insert into indexed_jar (md5, $indexedField) values (?, ?)",
            Statement.RETURN_GENERATED_KEYS,
          )
          insertStmt.setString(1, digest)
          insertStmt.setBoolean(2, true)
          insertStmt.executeUpdate()
          val rs = insertStmt.getGeneratedKeys
          rs.next()
          Some(rs.getInt("id"))
        } catch {
          case e: JdbcSQLIntegrityConstraintViolationException =>
            // Handle race condition where jar was inserted between check and insert
            scribe.warn(e)
            None
        } finally {
          if (insertStmt != null) insertStmt.close()
        }
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

  def getToplevelMembers(
      jar: AbsolutePath
  ): Option[Map[AbsolutePath, List[ToplevelMember]]] =
    try {
      val fs = getFileSystem(jar)
      val toplevelMembers = List.newBuilder[(AbsolutePath, ToplevelMember)]
      conn()
        .query(
          """select tm.symbol, tm.start_line, tm.start_character, tm.end_line, tm.end_character, tm.path, tm.kind
            |from indexed_jar ij
            |left join toplevel_members tm
            |on ij.id=tm.jar
            |where ij.toplevel_members_indexed=true and ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(jar)) } { rs =>
          if (rs.getString(1) != null) {
            val symbol = rs.getString(1)
            val startLine = rs.getInt(2)
            val startChar = rs.getInt(3)
            val endLine = rs.getInt(4)
            val endChar = rs.getInt(5)
            val path = AbsolutePath(fs.getPath(rs.getString(6)))
            val kind = SymbolInformation.Kind.fromValue(rs.getInt(7))
            import scala.meta.internal.semanticdb.Range
            val range = Range(startLine, startChar, endLine, endChar)
            toplevelMembers += (path -> ToplevelMember(symbol, range, kind))
          }
        }
        .headOption
        .map(_ =>
          toplevelMembers.result().groupBy(_._1).map {
            case (path, toplevelMembers) =>
              (path, toplevelMembers.map(_._2))
          }
        )
    } catch {
      case error @ (_: ZipError | _: ZipException) =>
        scribe.warn(s"corrupted jar $jar: $error")
        None
    }

  def addToplevelMembersInfo(
      path: AbsolutePath,
      toplevelMembers: Map[AbsolutePath, List[ToplevelMember]],
  ): Int = {
    val jar = addOrUpdateJar(path, "toplevel_members_indexed")
    jar.map(putToplevelMembersInfo(_, toplevelMembers)).getOrElse(0)
  }

  protected def putToplevelMembersInfo(
      jar: Int,
      toplevelMemberMap: Map[AbsolutePath, List[ToplevelMember]],
  ): Int =
    if (toplevelMemberMap.nonEmpty) {
      // Add type members for jar to H2
      var toplevelMemberStmt: PreparedStatement = null
      try {
        toplevelMemberStmt = conn().prepareStatement(
          s"insert into toplevel_members (symbol, start_line, start_character, end_line, end_character, path, kind, jar) values (?, ?, ?, ?, ?, ?, ?, ?)"
        )

        toplevelMemberMap.foreach { case (path, toplevelMembers) =>
          toplevelMembers.foreach { toplevelMember =>
            toplevelMemberStmt.setString(1, toplevelMember.symbol)
            toplevelMemberStmt.setInt(2, toplevelMember.range.startLine)
            toplevelMemberStmt.setInt(3, toplevelMember.range.startCharacter)
            toplevelMemberStmt.setInt(4, toplevelMember.range.endLine)
            toplevelMemberStmt.setInt(5, toplevelMember.range.endCharacter)
            toplevelMemberStmt.setString(
              6,
              path.toString,
            )
            toplevelMemberStmt.setInt(7, toplevelMember.kind.value)
            toplevelMemberStmt.setInt(8, jar)
            toplevelMemberStmt.addBatch()
          }
        }
        // Return number of rows inserted
        toplevelMemberStmt.executeBatch().sum
      } catch {
        case e: JdbcBatchUpdateException =>
          scribe.error(s"failed to insert toplevel members", e)
          0
        case e: JdbcSQLIntegrityConstraintViolationException =>
          scribe.error(s"failed to insert toplevel members", e)
          0
      } finally {
        if (toplevelMemberStmt != null) toplevelMemberStmt.close()
      }
    } else 0

  protected def putImplicitClassMembersInfo(
      jar: Int,
      implicitClassMemberMap: Map[AbsolutePath, List[ImplicitClassMember]],
  ): Int =
    if (implicitClassMemberMap.nonEmpty) {
      val totalClasses = implicitClassMemberMap.values.map(_.size).sum
      scribe.info(
        s"[JarTopLevels] Storing $totalClasses implicit classes from ${implicitClassMemberMap.size} files to database"
      )

      var implicitClassMemberStmt: PreparedStatement = null
      try {
        implicitClassMemberStmt = conn().prepareStatement(
          s"insert into implicit_class_members (class_symbol, jar) values (?, ?)"
        )

        implicitClassMemberMap.foreach { case (_, implicitClassMembers) =>
          implicitClassMembers.foreach { member =>
            scribe.debug(
              s"[JarTopLevels]   Storing: classSymbol=${member.classSymbol}"
            )
            implicitClassMemberStmt.setString(1, member.classSymbol)
            implicitClassMemberStmt.setInt(2, jar)
            implicitClassMemberStmt.addBatch()
          }
        }
        val inserted = implicitClassMemberStmt.executeBatch().sum
        scribe.info(
          s"[JarTopLevels] Successfully stored $inserted implicit classes"
        )
        inserted
      } catch {
        case e: JdbcBatchUpdateException =>
          scribe.error(s"failed to insert implicit class members", e)
          0
        case e: JdbcSQLIntegrityConstraintViolationException =>
          scribe.error(s"failed to insert implicit class members", e)
          0
      } finally {
        if (implicitClassMemberStmt != null) implicitClassMemberStmt.close()
      }
    } else 0

  def addImplicitClassMembersInfo(
      path: AbsolutePath,
      implicitClassMembers: Map[AbsolutePath, List[ImplicitClassMember]],
  ): Int = {
    val jar = addOrUpdateJar(path, "implicit_class_members_indexed")
    jar.map(putImplicitClassMembersInfo(_, implicitClassMembers)).getOrElse(0)
  }

  def getImplicitClassMembers(
      jar: AbsolutePath
  ): Option[Map[AbsolutePath, List[ImplicitClassMember]]] =
    try {
      scribe.info(
        s"[JarTopLevels] Reading implicit class symbols from database for JAR: $jar"
      )
      val implicitClasses = List.newBuilder[ImplicitClassMember]
      conn()
        .query(
          """select icm.class_symbol
            |from indexed_jar ij
            |left join implicit_class_members icm
            |on ij.id=icm.jar
            |where ij.implicit_class_members_indexed=true and ij.md5=?""".stripMargin
        ) { _.setString(1, getMD5Digest(jar)) } { rs =>
          if (rs.getString(1) != null) {
            val classSymbol = rs.getString(1)
            implicitClasses += ImplicitClassMember(classSymbol)
          }
        }
        .headOption
        .map { _ =>
          val classes = implicitClasses.result()
          scribe.info(
            s"[JarTopLevels] Retrieved ${classes.size} implicit classes from database"
          )
          // Return as Map with dummy path since we don't store paths anymore
          // WorkspaceSymbolProvider will handle this as a flat list
          Map(jar -> classes)
        }
    } catch {
      case error @ (_: ZipError | _: ZipException) =>
        scribe.warn(s"corrupted jar $jar: $error")
        None
    }

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
