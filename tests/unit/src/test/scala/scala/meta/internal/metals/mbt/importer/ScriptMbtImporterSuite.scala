package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files

import scala.concurrent.ExecutionContext

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import munit.FunSuite

class ScriptMbtImporterSuite extends FunSuite {

  private val noShellRunner: ShellRunner = null
  private val defaultUserConfig: () => UserConfiguration = () =>
    UserConfiguration()
  private implicit val ec: ExecutionContext = ExecutionContext.global

  private def makeScript(dir: AbsolutePath, filename: String): AbsolutePath = {
    val path = dir.resolve(filename)
    Files.writeString(path.toNIO, "// script")
    path
  }

  private def importer(scriptPath: AbsolutePath): ScriptMbtImporter =
    new ScriptMbtImporter(scriptPath, noShellRunner, defaultUserConfig)

  test("name-strips-mbt-scala") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-script-name"))
    val script = makeScript(dir, "my-exporter.mbt.scala")
    assertEquals(importer(script).name, "my-exporter-scala")
  }

  test("name-strips-mbt-java") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-script-name"))
    val script = makeScript(dir, "gen-deps.mbt.java")
    assertEquals(importer(script).name, "gen-deps-java")
  }

  test("name-strips-mbt-sh") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-script-name"))
    val script = makeScript(dir, "exporter.mbt.sh")
    assertEquals(importer(script).name, "exporter-sh")
  }

  test("name-strips-mbt-bat") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-script-name"))
    val script = makeScript(dir, "exporter.mbt.bat")
    assertEquals(importer(script).name, "exporter-bat")
  }

  test("name-unknown-extension-unchanged") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-script-name"))
    val script = makeScript(dir, "weird.txt")
    assertEquals(importer(script).name, "weird.txt")
  }

  test("output-path-uses-name") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-out"))
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-ws"))
    val script = makeScript(dir, "myexport.mbt.scala")
    val out = importer(script).outputPath(workspace)
    assertEquals(
      out.toNIO,
      workspace.toNIO.resolve(".metals/mbt-myexport-scala.json"),
    )
  }

  test("isWatchedFile-false-for-own-script-not-in-watchedFiles") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-related"))
    val script = makeScript(workspace, "export.mbt.sh")
    assert(!importer(script).isWatchedFile(script))
  }

  test("isWatchedFile-true-for-own-script-when-in-watchedFiles") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-related"))
    val script = makeScript(workspace, "export.mbt.sh")
    withCache(script, List("export.mbt.sh")) {
      assert(importer(script).isWatchedFile(script))
    }
  }

  test("isWatchedFile-false-for-unrelated-file") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-related"))
    val script = makeScript(workspace, "export.mbt.sh")
    val other = makeScript(workspace, "other.mbt.sh")
    assert(!importer(script).isWatchedFile(other))
  }

  test("buildCommand-sh-uses-sh") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val script = makeScript(dir, "run.mbt.sh")
    val cmd = importer(script).buildCommand(dir)
    assertEquals(cmd.head, "sh")
    assertEquals(cmd(1), script.toString)
  }

  test("buildCommand-bat-uses-script-directly") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val script = makeScript(dir, "run.mbt.bat")
    val cmd = importer(script).buildCommand(dir)
    assertEquals(cmd, List(script.toString))
  }

  test("buildCommand-scala-uses-scala-cli") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-cmd-ws"))
    val script = makeScript(dir, "run.mbt.scala")
    val cmd = importer(script).buildCommand(workspace)
    assertEquals(cmd.head, "scala-cli")
    assertEquals(cmd(1), "run")
    assertEquals(cmd.last, script.toString)
  }

  test("buildCommand-java-uses-scala-cli") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-cmd-ws"))
    val script = makeScript(dir, "run.mbt.java")
    val cmd = importer(script).buildCommand(workspace)
    assertEquals(cmd.head, "scala-cli")
  }

  test("buildCommand-custom-scala-cli-launcher") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-cmd-ws"))
    val script = makeScript(dir, "run.mbt.scala")
    val customConfig: () => UserConfiguration =
      () => UserConfiguration(scalaCliLauncher = Some("/opt/scala-cli"))
    val cmd =
      new ScriptMbtImporter(script, noShellRunner, customConfig).buildCommand(
        workspace
      )
    assertEquals(cmd.head, "/opt/scala-cli")
  }

  test("name-collision-same-base-different-extension-produces-distinct-names") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-collision"))
    val ws = AbsolutePath(Files.createTempDirectory("mbt-collision-ws"))
    val scalaScript = makeScript(dir, "foo.mbt.scala")
    val shScript = makeScript(dir, "foo.mbt.sh")
    val batScript = makeScript(dir, "foo.mbt.bat")
    val nameScala = importer(scalaScript).name
    val nameSh = importer(shScript).name
    val nameBat = importer(batScript).name
    assertNotEquals(nameScala, nameSh)
    assertNotEquals(nameScala, nameBat)
    assertNotEquals(nameSh, nameBat)
    assertNotEquals(
      importer(scalaScript).outputPath(ws),
      importer(shScript).outputPath(ws),
    )
    assertNotEquals(
      importer(scalaScript).outputPath(ws),
      importer(batScript).outputPath(ws),
    )
  }

  test("project-root-is-script-parent") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-root"))
    val script = makeScript(dir, "export.mbt.sh")
    assertEquals(importer(script).projectRoot, dir)
  }

  private def withCache(
      script: AbsolutePath,
      patterns: List[String],
  )(body: => Unit): Unit = {
    ScriptMbtImporter.setWatchedFiles(script, patterns)
    try body
    finally ScriptMbtImporter.setWatchedFiles(script, Nil)
  }

  test("isWatchedFile-watched-exact-filename") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-watched"))
    val script = makeScript(workspace, "export.mbt.sh")
    val buildSbt = makeScript(workspace, "build.sbt")
    withCache(script, List("build.sbt")) {
      assert(importer(script).isWatchedFile(buildSbt))
    }
  }

  test("isWatchedFile-watched-glob-pattern") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-watched-glob"))
    val script = makeScript(workspace, "export.mbt.sh")
    val subDir = workspace.resolve("sub")
    Files.createDirectories(subDir.toNIO)
    val gradleFile =
      AbsolutePath(Files.createFile(subDir.resolve("build.gradle").toNIO))
    withCache(script, List("**/*.gradle")) {
      assert(importer(script).isWatchedFile(gradleFile))
    }
  }

  test("isWatchedFile-unwatched-file-false") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-unwatched"))
    val script = makeScript(workspace, "export.mbt.sh")
    val other = makeScript(workspace, "pom.xml")
    withCache(script, List("build.sbt")) {
      assert(!importer(script).isWatchedFile(other))
    }
  }

  test("isWatchedFile-no-watched-files-false") {
    val workspace = AbsolutePath(Files.createTempDirectory("mbt-no-watched"))
    val script = makeScript(workspace, "export.mbt.sh")
    val other = makeScript(workspace, "build.sbt")
    // no cache populated → should return false
    assert(!importer(script).isWatchedFile(other))
  }
}
