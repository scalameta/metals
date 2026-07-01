package tests.bazel

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.{util => ju}

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtNamespace
import scala.meta.internal.metals.mbt.importer.BazelSrcjarSources
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class BazelSrcjarSourcesSuite extends BaseSuite {

  private def newWorkspace(): AbsolutePath =
    AbsolutePath(Files.createTempDirectory("srcjar-workspace"))

  private def writeSrcjar(
      workspace: AbsolutePath,
      relativePath: String,
      entries: (String, String)*
  ): Unit = {
    val path = workspace.resolve(relativePath).toNIO
    Files.createDirectories(path.getParent)
    val zip = new ZipOutputStream(Files.newOutputStream(path))
    try
      entries.foreach { case (name, content) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(content.getBytes("UTF-8"))
        zip.closeEntry()
      }
    finally zip.close()
  }

  test("extracts-only-sources-and-prevents-zip-slip") {
    val workspace = newWorkspace()
    writeSrcjar(
      workspace,
      "pkg/Lib.srcjar",
      "pkg/A.scala" -> "class A",
      "B.java" -> "class B {}",
      "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0",
      "../Evil.scala" -> "class Evil",
    )
    val Some(relativeDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    val outDir = workspace.resolve(relativeDir)
    assert(outDir.resolve("pkg/A.scala").isFile)
    assert(outDir.resolve("B.java").isFile)
    assert(
      !outDir.resolve("META-INF/MANIFEST.MF").isFile,
      "non-source kept out",
    )
    assert(
      !outDir.toNIO.getParent.resolve("Evil.scala").toFile.exists(),
      "zip-slip entry must not escape the extraction directory",
    )
  }

  test("distinct-srcjars-folding-to-the-same-name-get-distinct-dirs") {
    // `a/b.srcjar` and `a_b.srcjar` both fold to `a_b.srcjar` once the path
    // separators are replaced, so without the hash suffix they would share one
    // extraction directory and clobber each other.
    val workspace = newWorkspace()
    writeSrcjar(workspace, "a/b.srcjar", "A.scala" -> "class A")
    writeSrcjar(workspace, "a_b.srcjar", "B.scala" -> "class B")
    val Some(first) = BazelSrcjarSources.materialize(workspace, "a/b.srcjar")
    val Some(second) = BazelSrcjarSources.materialize(workspace, "a_b.srcjar")
    assertNotEquals(first, second)
    assert(workspace.resolve(first).resolve("A.scala").isFile)
    assert(workspace.resolve(second).resolve("B.scala").isFile)
    assert(!workspace.resolve(first).resolve("B.scala").isFile)
    assert(!workspace.resolve(second).resolve("A.scala").isFile)
  }

  test("re-extracts-only-when-the-srcjar-changes") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A")
    val Some(relativeDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    val extracted = workspace.resolve(relativeDir).resolve("A.scala").toNIO
    val firstMtime = Files.getLastModifiedTime(extracted)

    assertEquals(
      BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar"),
      Some(relativeDir),
    )
    assertEquals(
      Files.getLastModifiedTime(extracted),
      firstMtime,
      "unchanged srcjar must not be re-extracted",
    )

    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A2")
    Files.setLastModifiedTime(
      workspace.resolve("pkg/Lib.srcjar").toNIO,
      java.nio.file.attribute.FileTime.fromMillis(
        firstMtime.toMillis + 10_000
      ),
    )
    BazelSrcjarSources.materialize(workspace, "pkg/Lib.srcjar")
    assertNoDiff(Files.readString(extracted), "class A2")
  }

  test("materialize-all-rewrites-srcjar-sources-only") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Lib.srcjar", "A.scala" -> "class A")
    val namespace = MbtNamespace(
      sources = List("pkg/Dependent.scala", "pkg/Lib.srcjar").asJava,
      scalaVersion = "2.13.16",
    )
    val build = MbtBuild(
      ju.Collections.emptyList(),
      new ju.LinkedHashMap(ju.Map.of("//pkg", namespace)),
    )
    val rewritten = BazelSrcjarSources.materializeAll(workspace, build)
    val sources =
      rewritten.getNamespaces.get("//pkg").getSources.asScala.toList
    assertEquals(sources.head, "pkg/Dependent.scala")
    // The srcjar source is rewritten to its extraction directory, whose name
    // keeps a readable prefix and a content-addressed hash suffix (so distinct
    // srcjars never share a directory).
    assert(
      sources(1).startsWith(".metals/mbt-srcjar-sources/pkg_Lib.srcjar-"),
      s"unexpected rewritten srcjar source: ${sources(1)}",
    )
    // A srcjar that cannot be read keeps its original entry.
    val missing = namespace.copy(sources = List("pkg/Missing.srcjar").asJava)
    val kept = BazelSrcjarSources.materializeAll(
      workspace,
      MbtBuild(
        ju.Collections.emptyList(),
        new ju.LinkedHashMap(ju.Map.of("//pkg", missing)),
      ),
    )
    assertEquals(
      kept.getNamespaces.get("//pkg").getSources.asScala.toList,
      List("pkg/Missing.srcjar"),
    )
  }
}
