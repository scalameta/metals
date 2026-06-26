package tests.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.concurrent.ExecutionContext

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.importer.BazelMbtImporter
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
      shellRunner = new ShellRunner(
        Time.system,
        EmptyWorkDoneProgress,
        () => UserConfiguration(),
      ),
      ec = ec,
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
      languageClient = None,
      tables = None,
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
      languageClient = None,
      tables = None,
    )
    assertEquals(importers.length, 1)
    assert(importers.head.isInstanceOf[ScriptMbtImporter])
    assertEquals(importers.head.name, "gen-sh")
  }

  test("discover-mbt-bat-script") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("gen.mbt.bat"), "@echo off")

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
      languageClient = None,
      tables = None,
    )
    assertEquals(importers.length, 1)
    assert(importers.head.isInstanceOf[ScriptMbtImporter])
    assertEquals(importers.head.name, "gen-bat")
  }

  test("discover-multiple-scripts") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("a.mbt.scala"))
    write(workspace.resolve("b.mbt.sh"))
    write(workspace.resolve("c.mbt.java"))
    write(workspace.resolve("d.mbt.bat"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
      languageClient = None,
      tables = None,
    )
    val scriptImporters = importers.collect { case s: ScriptMbtImporter => s }
    assertEquals(scriptImporters.length, 4)
    assertEquals(
      scriptImporters.map(_.name).toSet,
      Set("a-scala", "b-sh", "c-java", "d-bat"),
    )
  }

  test("non-mbt-files-not-discovered") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))
    write(workspace.resolve("exporter.scala"))
    write(workspace.resolve("exporter.sh"))
    write(workspace.resolve("exporter.java"))
    write(workspace.resolve("exporter.bat"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
      languageClient = None,
      tables = None,
    )
    val scriptImporters = importers.collect { case s: ScriptMbtImporter => s }
    assertEquals(scriptImporters.length, 0)
  }

  test("empty-workspace-no-importers") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-disco"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
      languageClient = None,
      tables = None,
    )
    assertEquals(importers.length, 0)
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

  test("discover-bazel-importer") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-bazel"))
    write(workspace.resolve("WORKSPACE"))

    val importers = buildTools(workspace).mbtImporters(
      shellRunner = null,
      userConfig = () => UserConfiguration(),
      languageClient = None,
      tables = None,
    )
    assertEquals(importers.length, 1)
    assert(importers.head.isInstanceOf[BazelMbtImporter])
    assertEquals(importers.head.name, "bazel")
  }

  test("hasMbtImporters-true-for-bazel-workspace") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-bazel"))
    write(workspace.resolve("WORKSPACE"), "")
    assert(buildTools(workspace).hasMbtImporters)
  }

  test("bazel-project-view-target-patterns") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-bazel-pv"))
    write(workspace.resolve("WORKSPACE"))
    write(
      workspace.resolve(".bazelproject"),
      """targets:
        |    //apps/...
        |build_manual_targets: false
        |""".stripMargin,
    )
    assertEquals(
      BazelProjectViewTargets.patterns(workspace),
      List("//apps/..."),
    )
  }
}
