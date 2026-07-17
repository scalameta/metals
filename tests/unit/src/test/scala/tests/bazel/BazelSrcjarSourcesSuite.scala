package tests.bazel

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.{util => ju}

import scala.jdk.CollectionConverters._
import scala.util.Using

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
    Using.resource(new ZipOutputStream(Files.newOutputStream(path)))(zip =>
      entries.foreach { case (name, content) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(content.getBytes("UTF-8"))
        zip.closeEntry()
      }
    )
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
      ju.Collections.emptyList(),
    )
    val rewritten = BazelSrcjarSources.materializeAll(workspace, build)
    val sources =
      rewritten.getNamespaces.get("//pkg").getSources.asScala.toList
    assertEquals(sources.head, "pkg/Dependent.scala")
    assert(
      sources(1).startsWith(".metals/mbt-srcjar-sources/pkg_Lib.srcjar-"),
      s"unexpected rewritten srcjar source: ${sources(1)}",
    )
    val missing = namespace.copy(sources = List("pkg/Missing.srcjar").asJava)
    val kept = BazelSrcjarSources.materializeAll(
      workspace,
      MbtBuild(
        ju.Collections.emptyList(),
        new ju.LinkedHashMap(ju.Map.of("//pkg", missing)),
        ju.Collections.emptyList(),
      ),
    )
    assertEquals(
      kept.getNamespaces.get("//pkg").getSources.asScala.toList,
      List("pkg/Missing.srcjar"),
    )
  }

  test("materialize-all-prunes-stale-extraction-dirs") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Old.srcjar", "Old.scala" -> "class Old")
    writeSrcjar(workspace, "pkg/New.srcjar", "New.scala" -> "class New")
    val Some(oldDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Old.srcjar")
    assert(workspace.resolve(oldDir).resolve("Old.scala").isFile)

    val namespace = MbtNamespace(
      sources = List("pkg/New.srcjar").asJava,
      scalaVersion = "2.13.16",
    )
    val rewritten = BazelSrcjarSources.materializeAll(
      workspace,
      MbtBuild(
        ju.Collections.emptyList(),
        new ju.LinkedHashMap(ju.Map.of("//pkg", namespace)),
        ju.Collections.emptyList(),
      ),
    )
    assert(
      !workspace.resolve(oldDir).toFile.exists(),
      "stale extraction dir must be pruned",
    )
    val newDir =
      rewritten.getNamespaces.get("//pkg").getSources.asScala.head
    assert(workspace.resolve(newDir).resolve("New.scala").isFile)
  }

  test("materialize-all-empty-build-does-not-prune") {
    val workspace = newWorkspace()
    writeSrcjar(workspace, "pkg/Kept.srcjar", "Kept.scala" -> "class Kept")
    val Some(keptDir) =
      BazelSrcjarSources.materialize(workspace, "pkg/Kept.srcjar")
    assert(workspace.resolve(keptDir).resolve("Kept.scala").isFile)

    // MbtImport discards an empty build.
    BazelSrcjarSources.materializeAll(
      workspace,
      MbtBuild(
        ju.Collections.emptyList(),
        new ju.LinkedHashMap(),
        ju.Collections.emptyList(),
      ),
    )
    assert(
      workspace.resolve(keptDir).resolve("Kept.scala").isFile,
      "empty build must not prune extraction dirs of the preserved mbt.json",
    )
  }
}
