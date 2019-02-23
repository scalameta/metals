package tests

import scala.collection.concurrent.TrieMap

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.JarTopLevels
import scala.meta.internal.io.PlatformFileIO

object JarTopLevelsSuite extends BaseTablesSuite {
  def jarSymbols: JarTopLevels = tables.jarSymbols
  val jar1 = AbsolutePath(getClass.getResource("/jar-symbols/jar1.zip").getFile)
  val jar2 = AbsolutePath(getClass.getResource("/jar-symbols/jar2.zip").getFile)

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
