package tests

import java.nio.file.Files
import java.nio.file.Path

import scala.sys.process

import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.io.AbsolutePath

class TemporaryDirectoryFixture extends munit.Fixture[AbsolutePath]("tmp-dir") {
  private var p: Path = null
  def apply(): AbsolutePath = AbsolutePath(p)
  override def beforeEach(context: munit.BeforeEach): Unit = {
    val name = context.test.name.replaceAll("[^a-zA-Z0-9]", "-")
    p = Files.createTempDirectory(name)
  }
  override def afterEach(context: munit.AfterEach): Unit = {
    AbsolutePath(p).deleteRecursively()
  }

  def gitCommitAllChanges(message: String = "Commit all changes"): Unit = {
    executeCommand("git add .")
    executeCommand(
      s"git commit --no-gpg-sign --no-verify -m '$message'"
    )
  }
  def executeCommand(command: String): Unit = {
    process.Process(command, cwd = p.toFile).!!
  }
}
