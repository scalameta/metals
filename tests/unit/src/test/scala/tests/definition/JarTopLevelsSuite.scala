package tests.definition

import scala.collection.concurrent.TrieMap

import java.nio.file.{Path, Files}
import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.JarTopLevels
import scala.meta.internal.io.PlatformFileIO

object JarTopLevelsSuite extends BaseTablesSuite {
  private def jarSymbols: JarTopLevels = tables.jarSymbols
  private val tmp: Path = Files.createTempDirectory("metals")
  private val jar1: AbsolutePath = createSourceZip(tmp, "jar1.zip")
  private val jar2: AbsolutePath = createSourceZip(tmp, "jar2.zip")

  private def createSourceZip(dir: Path, name: String): AbsolutePath = {
    val zip = AbsolutePath(dir.resolve(name))
    FileIO.withJarFileSystem(zip, create = true, close = true) { root =>
      FileLayout.fromString(
        """|/foo.scala
           |object Hello {
           |}""".stripMargin,
        root
      )
    }
    zip
  }

  test("cachedSymbols") {
    val fs = PlatformFileIO.newJarFileSystem(jar1, create = false)
    val filePath = AbsolutePath(fs.getPath("/foo.scala"))
    val map = TrieMap[String, AbsolutePath]("foo" -> filePath)
    jarSymbols.putTopLevels(jar1, map)
    val resultOption = jarSymbols.getTopLevels(jar1)
    assert(resultOption.isDefined)
    val result = resultOption.get
    assert(map("foo") == result("foo"))
    assert(result.get("bar").isEmpty)
    val noOption = jarSymbols.getTopLevels(jar2)
    assert(noOption.isEmpty)
  }

  test("deleteNotUsed") {
    Seq(jar1, jar2).foreach { jar =>
      val fs = PlatformFileIO.newJarFileSystem(jar, create = false)
      val filePath = AbsolutePath(fs.getPath("/foo.scala"))
      val map = TrieMap[String, AbsolutePath]("foo" -> filePath)
      jarSymbols.putTopLevels(jar, map)
    }
    jarSymbols.deleteNotUsedTopLevels(Array(jar1, jar1))
    assert(jarSymbols.getTopLevels(jar1).isDefined)
    assert(jarSymbols.getTopLevels(jar2).isEmpty)
  }

  test("noSymbols") {
    jarSymbols.putTopLevels(jar1, TrieMap[String, AbsolutePath]())
    val result = jarSymbols.getTopLevels(jar1)
    assert(result.isDefined)
    assert(result.get.isEmpty)
  }
}
