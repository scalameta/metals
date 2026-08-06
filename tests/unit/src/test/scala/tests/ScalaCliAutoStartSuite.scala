package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.metals.scalacli.ScalaCliAutoStart
import scala.meta.io.AbsolutePath

class ScalaCliAutoStartSuite extends BaseSuite {

  private def tempDir(name: String): AbsolutePath = {
    val dir = AbsolutePath(Files.createTempDirectory(name))
    dir.toFile.deleteOnExit()
    dir
  }

  private def writeFile(path: AbsolutePath, content: String): AbsolutePath = {
    Files.createDirectories(path.parent.toNIO)
    Files.write(path.toNIO, content.getBytes(StandardCharsets.UTF_8))
    path.toFile.deleteOnExit()
    path
  }

  test("outside-workspace-folder-skips-auto-start") {
    val workspace = tempDir("metals-ws")
    val outsider = tempDir("metals-outsider")
    val scalaFile = writeFile(outsider.resolve("Foo.scala"), "object Foo")

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
    val scalaFile =
      writeFile(workspace.resolve("src").resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(workspace)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(scalaFile, Seq(workspace)),
      false,
    )
  }

  test("nested-workspace-folder-still-allows-auto-start") {
    val parent = tempDir("metals-parent")
    val child = parent.resolve("child")
    Files.createDirectories(child.toNIO)
    child.toFile.deleteOnExit()
    val scalaFile = writeFile(child.resolve("Foo.scala"), "object Foo")

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
    val scalaFile = writeFile(sibling.resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(project)),
      false,
    )
  }

  test("empty-workspace-folders-keeps-legacy-auto-start") {
    val outsider = tempDir("metals-empty-folders")
    val scalaFile = writeFile(outsider.resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq.empty),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(scalaFile, Seq.empty),
      false,
    )
  }

  test("non-scala-files-never-auto-start") {
    val workspace = tempDir("metals-non-scala")
    val md = writeFile(workspace.resolve("README.md"), "# hi")
    val java = writeFile(workspace.resolve("Main.java"), "class Main {}")

    assertEquals(ScalaCliAutoStart.shouldAutoStart(md, Seq(workspace)), false)
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(java, Seq(workspace)),
      false,
    )
  }

  test("scala-script-inside-workspace-allows-auto-start") {
    val workspace = tempDir("metals-script")
    val script = writeFile(workspace.resolve("script.sc"), "println(1)")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(script, Seq(workspace)),
      true,
    )
  }

  test("scala-script-outside-workspace-skips-auto-start") {
    val workspace = tempDir("metals-script-ws")
    val outsider = tempDir("metals-script-out")
    val script = writeFile(outsider.resolve("script.sc"), "println(1)")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(script, Seq(workspace)),
      false,
    )
  }

  test("path-under-non-scala-folder-allows-auto-start") {
    val docs = tempDir("metals-docs")
    val scalaFile = writeFile(docs.resolve("Snippet.scala"), "object Snippet")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(docs)),
      true,
    )
  }

  test("path-equal-to-workspace-folder-is-not-outside") {
    val workspace = tempDir("metals-exact")

    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(workspace, Seq(workspace)),
      false,
    )
  }

  test("shared-prefix-sibling-directory-is-outside") {
    // Path.startsWith is component-aware: /tmp/foo must not match /tmp/foobar.
    val root = tempDir("metals-prefix")
    val project = root.resolve("foo")
    val lookalike = root.resolve("foobar")
    Files.createDirectories(project.toNIO)
    Files.createDirectories(lookalike.toNIO)
    project.toFile.deleteOnExit()
    lookalike.toFile.deleteOnExit()
    val scalaFile = writeFile(lookalike.resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(scalaFile, Seq(project)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(project)),
      false,
    )
  }

  test("file-in-one-of-several-folders-allows-auto-start") {
    val a = tempDir("metals-multi-a")
    val b = tempDir("metals-multi-b")
    val c = tempDir("metals-multi-c")
    val scalaFile = writeFile(b.resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(a, b, c)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(
        writeFile(
          tempDir("metals-multi-out").resolve("Bar.scala"),
          "object Bar",
        ),
        Seq(a, b, c),
      ),
      false,
    )
  }

  test("worksheet-and-sbt-inside-workspace-allow-auto-start") {
    val workspace = tempDir("metals-special")
    val worksheet =
      writeFile(workspace.resolve("Main.worksheet.sc"), "val x = 1")
    val sbt = writeFile(workspace.resolve("build.sbt"), "name := \"x\"")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(worksheet, Seq(workspace)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(sbt, Seq(workspace)),
      true,
    )
  }

  test("deeply-nested-outside-path-skips-auto-start") {
    val workspace = tempDir("metals-deep-ws")
    val outsider = tempDir("metals-deep-out")
    val scalaFile = writeFile(
      outsider.resolve("a").resolve("b").resolve("c").resolve("Foo.scala"),
      "object Foo",
    )

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(workspace)),
      false,
    )
  }

  test("symlinked-workspace-folder-with-matching-link-path-allows-auto-start") {
    val root = tempDir("metals-symlink-ws")
    val original = root.resolve("original")
    Files.createDirectories(original.toNIO)
    original.toFile.deleteOnExit()
    val link = AbsolutePath(
      Files.createSymbolicLink(root.resolve("link").toNIO, original.toNIO)
    )
    link.toFile.deleteOnExit()
    val scalaFile = writeFile(link.resolve("Foo.scala"), "object Foo")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(scalaFile, Seq(link)),
      true,
    )
  }

  test("symlinked-workspace-membership-is-lexical-not-canonical") {
    val root = tempDir("metals-symlink-lex")
    val original = root.resolve("original")
    Files.createDirectories(original.toNIO)
    original.toFile.deleteOnExit()
    val link = AbsolutePath(
      Files.createSymbolicLink(root.resolve("link").toNIO, original.toNIO)
    )
    link.toFile.deleteOnExit()
    val viaLink = writeFile(link.resolve("Foo.scala"), "object Foo")
    val viaReal = original.resolve("Foo.scala")

    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(viaLink, Seq(link)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(viaReal, Seq(link)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(viaReal, Seq(link)),
      false,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(viaLink, Seq(original)),
      false,
    )
  }

  test("symlink-into-workspace-from-outside-is-outside-lexically") {
    val workspace = tempDir("metals-symlink-in")
    val outside = tempDir("metals-symlink-out")
    val target = writeFile(workspace.resolve("Foo.scala"), "object Foo")
    val link = AbsolutePath(
      Files.createSymbolicLink(outside.resolve("Foo.scala").toNIO, target.toNIO)
    )
    link.toFile.deleteOnExit()

    assertEquals(
      ScalaCliAutoStart.isOutsideWorkspace(link, Seq(workspace)),
      true,
    )
    assertEquals(
      ScalaCliAutoStart.shouldAutoStart(link, Seq(workspace)),
      false,
    )
  }
}
