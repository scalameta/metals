package tests.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.ExecutionContext

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.importer.GradleMbtImporter
import scala.meta.internal.metals.mbt.importer.MavenMbtImporter
import scala.meta.internal.metals.mbt.importer.ScriptMbtImporter
import scala.meta.io.AbsolutePath

import tests.BaseSuite

class MbtImporterDiscoverySuite extends BaseSuite {

  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def buildTools(workspace: AbsolutePath): BuildTools =
    new BuildTools(
      workspace,
      bspGlobalDirectories = Nil,
      userConfig = () => UserConfiguration(),
      explicitChoiceMade = () => false,
      charset = StandardCharsets.UTF_8,
    )

  private def write(path: AbsolutePath, content: String = ""): Unit = {
    Files.createDirectories(path.toNIO.getParent)
    Files.writeString(path.toNIO, content)
  }

  test("discover-mbt-scala-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("exporter.mbt.scala"), "// script")

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    assertEquals(importers.length, 1)
    assert(importers.head.isInstanceOf[ScriptMbtImporter])
    assertEquals(importers.head.name, "exporter-scala")
  }

  test("discover-mbt-sh-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("gen.mbt.sh"), "#!/bin/sh")

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    assertEquals(importers.length, 1)
    assert(importers.head.isInstanceOf[ScriptMbtImporter])
    assertEquals(importers.head.name, "gen-sh")
  }

  test("discover-multiple-scripts") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("a.mbt.scala"))
    write(workspace.resolve("b.mbt.sh"))
    write(workspace.resolve("c.mbt.java"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    val scriptImporters = importers.collect { case s: ScriptMbtImporter => s }
    assertEquals(scriptImporters.length, 3)
    assertEquals(
      scriptImporters.map(_.name).toSet,
      Set("a-scala", "b-sh", "c-java"),
    )
  }

  test("non-mbt-files-not-discovered") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("exporter.scala"))
    write(workspace.resolve("exporter.sh"))
    write(workspace.resolve("exporter.java"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    val scriptImporters = importers.collect { case s: ScriptMbtImporter => s }
    assertEquals(scriptImporters.length, 0)
  }

  test("discover-maven-importer") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("pom.xml"), "<project/>")

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    assert(importers.exists(_.isInstanceOf[MavenMbtImporter]))
  }

  test("discover-gradle-importer") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("build.gradle"), "plugins {}")

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    assert(importers.exists(_.isInstanceOf[GradleMbtImporter]))
  }

  test("empty-workspace-no-importers") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
    )
    assertEquals(importers.length, 0)
  }

  test("maven-isBuildRelated-true-for-pom-xml") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-related"))
    write(workspace.resolve("pom.xml"), "<project/>")
    val importer = buildTools(workspace)
      .mbtImporters(null, () => UserConfiguration())
      .collectFirst { case m: MavenMbtImporter => m }
      .get
    assert(importer.isBuildRelated(workspace.resolve("pom.xml")))
  }

  test("gradle-isBuildRelated-true-for-build-gradle") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-related"))
    write(workspace.resolve("build.gradle"), "plugins {}")
    val importer = buildTools(workspace)
      .mbtImporters(null, () => UserConfiguration())
      .collectFirst { case g: GradleMbtImporter => g }
      .get
    assert(importer.isBuildRelated(workspace.resolve("build.gradle")))
  }

  test("hasMbtImporters-true-for-pom-xml") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-has"))
    write(workspace.resolve("pom.xml"), "<project/>")
    assert(buildTools(workspace).hasMbtImporters)
  }

  test("hasMbtImporters-true-for-build-gradle") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-has"))
    write(workspace.resolve("build.gradle"), "plugins {}")
    assert(buildTools(workspace).hasMbtImporters)
  }

  test("hasMbtImporters-true-for-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-has"))
    write(workspace.resolve("export.mbt.scala"), "// script")
    assert(buildTools(workspace).hasMbtImporters)
  }

  test("hasMbtImporters-false-for-empty-workspace") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-has"))
    assert(!buildTools(workspace).hasMbtImporters)
  }
}
