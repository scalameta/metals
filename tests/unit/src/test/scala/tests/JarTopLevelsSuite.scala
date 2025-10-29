package tests

import java.nio.file.Files
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.Statement

import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PlatformFileIO
import scala.meta.internal.metals.JarTopLevels
import scala.meta.internal.mtags.TopLevelMember
import scala.meta.internal.mtags.UnresolvedOverriddenSymbol
import scala.meta.internal.semanticdb.Range
import scala.meta.io.AbsolutePath

class JarTopLevelsSuite extends BaseTablesSuite {
  private def jarSymbols: JarTopLevels = tables.jarSymbols
  private val tmp: Path = Files.createTempDirectory("metals")
  private val jar1: AbsolutePath = createSourceZip(tmp, "jar1.zip")
  private val jar2: AbsolutePath = createSourceZip(tmp, "jar2.zip")

  private def createSourceZip(dir: Path, name: String): AbsolutePath = {
    val zip = AbsolutePath(dir.resolve(name))
    FileIO.withJarFileSystem(zip, create = true, close = true) { root =>
      FileLayout.fromString(
        """|/foo.scala
           |case class Hello(i: Int) extends AnyVal {
           |}""".stripMargin,
        root,
      )
    }
    zip
  }

  test("cachedSymbols") {
    val fs = PlatformFileIO.newJarFileSystem(jar1, create = false)
    val filePath = AbsolutePath(fs.getPath("/foo.scala"))
    val toplevels = List("foo" -> filePath)
    val overrides =
      List((filePath, "foo/Hello#", UnresolvedOverriddenSymbol("AnyVal")))
    jarSymbols.putJarIndexingInfo(jar1, toplevels, overrides)
    val resultOption = jarSymbols.getTopLevels(jar1)
    assert(resultOption.isDefined)
    val result = resultOption.get
    assert(toplevels == result)
    val resultOption1 = jarSymbols.getTypeHierarchy(jar1)
    assert(resultOption1.isDefined)
    val result1 = resultOption1.get
    assert(overrides == result1)
    val noOption = jarSymbols.getTopLevels(jar2)
    assert(noOption.isEmpty)
    assert(jarSymbols.getTypeHierarchy(jar2).isEmpty)
  }

  test("deleteNotUsed") {
    Seq(jar1, jar2).foreach { jar =>
      val fs = PlatformFileIO.newJarFileSystem(jar, create = false)
      val filePath = AbsolutePath(fs.getPath("/foo.scala"))
      val toplevels = List("foo" -> filePath)
      val overrides =
        List((filePath, "foo/Hello#", UnresolvedOverriddenSymbol("AnyVal")))
      jarSymbols.putJarIndexingInfo(jar, toplevels, overrides)
    }
    jarSymbols.deleteNotUsedTopLevels(Array(jar1, jar1))
    assert(jarSymbols.getTopLevels(jar1).isDefined)
    assert(jarSymbols.getTypeHierarchy(jar1).isDefined)
    assert(jarSymbols.getTopLevels(jar2).isEmpty)
    assert(jarSymbols.getTypeHierarchy(jar2).isEmpty)
  }

  test("noSymbols") {
    jarSymbols.putJarIndexingInfo(jar1, List.empty, List.empty)
    val result = jarSymbols.getTopLevels(jar1)
    assert(result.isEmpty)
  }

  test("addTypeHierarchy") {
    val fs = PlatformFileIO.newJarFileSystem(jar1, create = false)
    val filePath = AbsolutePath(fs.getPath("/foo.scala"))
    val toplevels = List("foo" -> filePath)
    val overrides =
      List((filePath, "foo/Hello#", UnresolvedOverriddenSymbol("AnyVal")))

    var jarStmt: PreparedStatement = null
    val jar =
      try {
        jarStmt = tables
          .connect()
          .prepareStatement(
            s"insert into indexed_jar (md5) values (?)",
            Statement.RETURN_GENERATED_KEYS,
          )
        jarStmt.setString(1, tables.jarSymbols.getMD5Digest(jar1))
        jarStmt.executeUpdate()
        val rs = jarStmt.getGeneratedKeys
        rs.next()
        rs.getInt("id")
      } finally {
        if (jarStmt != null) jarStmt.close()
      }
    tables.jarSymbols.putToplevels(jar, toplevels)

    assert(jarSymbols.getTopLevels(jar1).nonEmpty)
    assert(jarSymbols.getTypeHierarchy(jar1).isEmpty)

    jarSymbols.addTypeHierarchyInfo(jar1, overrides)
    val obtainedTopLevels = jarSymbols.getTopLevels(jar1)
    assert(obtainedTopLevels.nonEmpty)
    assert(obtainedTopLevels.get == toplevels)
    val obtainedTypeHierarchy = jarSymbols.getTypeHierarchy(jar1)
    assert(obtainedTypeHierarchy.nonEmpty)
    assert(obtainedTypeHierarchy.get == overrides)
  }

  test("addToplevelMembers") {
    val fs = PlatformFileIO.newJarFileSystem(jar1, create = false)
    val filePath = AbsolutePath(fs.getPath("/foo.scala"))
    val toplevels = List("foo" -> filePath)
    val overrides =
      List.empty[(AbsolutePath, String, UnresolvedOverriddenSymbol)]
    val toplevelMembers = Map(
      filePath -> List(
        TopLevelMember(
          "foo/package.Bye#",
          Range(1, 0, 2, 1),
          TopLevelMember.Kind.Type,
        ),
        TopLevelMember(
          "foo/package.Hello#",
          Range(1, 0, 2, 1),
          TopLevelMember.Kind.Type,
        ),
        TopLevelMember(
          "foo/package.Hello#",
          Range(1, 0, 2, 1),
          TopLevelMember.Kind.Type,
        ),
        TopLevelMember(
          "foo/package.Bye#",
          Range(1, 0, 2, 1),
          TopLevelMember.Kind.Type,
        ),
      )
    )

    jarSymbols.putJarIndexingInfo(jar1, toplevels, overrides, toplevelMembers)

    val obtainedToplevelMembers = jarSymbols.getToplevelMembers(jar1)
    assert(obtainedToplevelMembers.nonEmpty)
    assert(obtainedToplevelMembers.get == toplevelMembers)
  }
}
