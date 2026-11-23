package tests

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.meta.dialects
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

/** Kotlin sources in the symbol index that resolves go-to-definition. */
class KotlinDefinitionIndexSuite extends BaseSuite {

  private def definitionFile(index: OnDemandSymbolIndex, sym: String): String =
    index
      .definition(Symbol(sym))
      .map(_.path.toNIO.getFileName.toString)
      .getOrElse(throw new NoSuchElementException(sym))

  test("short-file-name") {
    val index = OnDemandSymbolIndex.empty()
    val root = FileLayout.fromString(
      """|/A.kt
         |class A
         |""".stripMargin
    )
    index.addToplevelSymbol(
      "A.kt",
      root.resolve("A.kt"),
      "_empty_/A#",
      dialects.Scala213,
    )
    assertEquals(definitionFile(index, "_empty_/A#"), "A.kt")
  }

  test("source-jar") {
    val jar = Files.createTempFile("okhttp-sources", ".jar")
    val zip = new ZipOutputStream(Files.newOutputStream(jar))
    zip.putNextEntry(new ZipEntry("okhttp3/Client.kt"))
    zip.write(
      """|package okhttp3
         |
         |open class OkHttpClient {
         |  fun newCall(url: String): String = url
         |}
         |
         |fun defaultClient(): OkHttpClient = OkHttpClient()
         |""".stripMargin.getBytes("UTF-8")
    )
    zip.closeEntry()
    zip.close()
    val index = OnDemandSymbolIndex.empty()
    index.addSourceJar(AbsolutePath(jar), dialects.Scala213)
    assertEquals(definitionFile(index, "okhttp3/OkHttpClient#"), "Client.kt")
    assertEquals(
      definitionFile(index, "okhttp3/OkHttpClient#newCall()."),
      "Client.kt",
    )
    assertEquals(definitionFile(index, "okhttp3/defaultClient()."), "Client.kt")
  }

  test("source-directory") {
    val index = OnDemandSymbolIndex.empty()
    val root = FileLayout.fromString(
      """|/a/A.kt
         |package a
         |class C
         |""".stripMargin
    )
    index.addSourceDirectory(root, dialects.Scala213)
    assertEquals(definitionFile(index, "a/C#"), "A.kt")
  }
}
