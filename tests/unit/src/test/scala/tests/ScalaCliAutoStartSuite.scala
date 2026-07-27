package tests

import java.nio.file.Files

import scala.meta.internal.metals.scalacli.ScalaCliAutoStart
import scala.meta.io.AbsolutePath

class ScalaCliAutoStartSuite extends BaseSuite {

  private def tempDir(name: String): AbsolutePath = {
    val dir = AbsolutePath(Files.createTempDirectory(name))
    dir.toFile.deleteOnExit()
    dir
  }

  test("outside-workspace-folder-skips-auto-start") {
    val workspace = tempDir("metals-ws")
    val outsider = tempDir("metals-outsider")
    val scalaFile = outsider.resolve("Foo.scala")
    Files.write(scalaFile.toNIO, "object Foo".getBytes)
    scalaFile.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(scalaFile, Seq(workspace)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(workspace)),
      false,
    )
  }

  test("inside-workspace-folder-allows-auto-start") {
    val workspace = tempDir("metals-ws-in")
    val scalaFile = workspace.resolve("src").resolve("Foo.scala")
    Files.createDirectories(scalaFile.parent.toNIO)
    Files.write(scalaFile.toNIO, "object Foo".getBytes)
    scalaFile.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(workspace)),
      true,
    )
  }

  test("nested-workspace-folder-match-uses-deepest-prefix") {
    val parent = tempDir("metals-parent")
    val child = parent.resolve("child")
    Files.createDirectories(child.toNIO)
    child.toFile.deleteOnExit()
    val scalaFile = child.resolve("Foo.scala")
    Files.write(scalaFile.toNIO, "object Foo".getBytes)
    scalaFile.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(parent, child)),
      true,
    )
  }

  test("sibling-directory-is-outside-workspace") {
    val root = tempDir("metals-root")
    val project = root.resolve("project")
    val sibling = root.resolve("sibling")
    Files.createDirectories(project.toNIO)
    Files.createDirectories(sibling.toNIO)
    project.toFile.deleteOnExit()
    sibling.toFile.deleteOnExit()
    val scalaFile = sibling.resolve("Foo.scala")
    Files.write(scalaFile.toNIO, "object Foo".getBytes)
    scalaFile.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(project)),
      false,
    )
  }

  test("empty-workspace-folders-keeps-legacy-auto-start") {
    val outsider = tempDir("metals-empty-folders")
    val scalaFile = outsider.resolve("Foo.scala")
    Files.write(scalaFile.toNIO, "object Foo".getBytes)
    scalaFile.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq.empty),
      true,
    )
  }

  test("non-scala-files-never-auto-start") {
    val workspace = tempDir("metals-non-scala")
    val md = workspace.resolve("README.md")
    Files.write(md.toNIO, "# hi".getBytes)
    md.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(md, Seq(workspace)),
      false,
    )
  }

  test("scala-script-inside-workspace-allows-auto-start") {
    val workspace = tempDir("metals-script")
    val script = workspace.resolve("script.sc")
    Files.write(script.toNIO, "println(1)".getBytes)
    script.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(script, Seq(workspace)),
      true,
    )
  }
}
