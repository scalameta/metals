package tests

import scala.collection.concurrent.TrieMap
import java.nio.file.Paths

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.JarTopLevels
import scala.meta.internal.io.PlatformFileIO

object JarTopLevelsSuite extends BaseTablesSuite {
  def jarSymbols: JarTopLevels = tables.jarSymbols
  def getAbsolutePath(resource: String): AbsolutePath = {
    AbsolutePath(Paths.get(getClass.getResource(resource).toURI))
  }
  val jar1 = getAbsolutePath("/jar-symbols/jar1.zip")
  val jar2 = getAbsolutePath("/jar-symbols/jar2.zip")

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
