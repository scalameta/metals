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

  test("isBuildRelated-true-for-own-script") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-related"))
    val script = makeScript(dir, "export.mbt.sh")
    assert(importer(script).isBuildRelated(script))
  }

  test("isBuildRelated-false-for-other-file") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-related"))
    val script = makeScript(dir, "export.mbt.sh")
    val other = makeScript(dir, "other.mbt.sh")
    assert(!importer(script).isBuildRelated(other))
  }

  test("buildCommand-sh-uses-sh") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-cmd"))
    val script = makeScript(dir, "run.mbt.sh")
    val cmd = importer(script).buildCommand(dir)
    assertEquals(cmd.head, "sh")
    assertEquals(cmd(1), script.toString)
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
    val nameScala = importer(scalaScript).name
    val nameSh = importer(shScript).name
    assertNotEquals(nameScala, nameSh)
    assertNotEquals(
      importer(scalaScript).outputPath(ws),
      importer(shScript).outputPath(ws),
    )
  }

  test("project-root-is-script-parent") {
    val dir = AbsolutePath(Files.createTempDirectory("mbt-root"))
    val script = makeScript(dir, "export.mbt.sh")
    assertEquals(importer(script).projectRoot, dir)
  }
}
