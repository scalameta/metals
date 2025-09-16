package tests.mbt

import scala.util.Using

import scala.meta.internal.metals.mbt.GitBlob
import scala.meta.internal.metals.mbt.GitCat
import scala.meta.internal.metals.mbt.GitVCS

import munit.AnyFixture
import tests.FileLayout
import tests.TemporaryDirectoryFixture

class GitVSCSuite extends munit.FunSuite {
  val workspace = new TemporaryDirectoryFixture()
  override def munitFixtures: Seq[AnyFixture[_]] = List(workspace)

  def lsFilesStage(): List[GitBlob] = {
    GitVCS.lsFilesStage(workspace(), _ => true).sortBy(_.path).toList
  }

  def assertLsFiles(expected: String)(implicit loc: munit.Location): Unit = {
    assertNoDiff(lsFilesStage().map(_.path).mkString("\n"), expected)
  }

  test("multi-file-repo") {
    workspace.executeCommand("git init -b main")
    assertEquals(lsFilesStage(), List())
    FileLayout.fromString(
      """
/src/main/scala/com/Hello.scala
object Hello
/src/main/java/com/Greeting.java
class Greeting
/src/main/proto/com/User.proto
message User { }
/README.md
# Example Project
""",
      root = workspace(),
    )
    workspace.executeCommand("git add .")
    workspace.executeCommand(
      "git commit --no-gpg-sign --no-verify -m 'Initial commit'"
    )
    val initialFiles = """
README.md
src/main/java/com/Greeting.java
src/main/proto/com/User.proto
src/main/scala/com/Hello.scala
"""
    assertLsFiles(initialFiles)

    FileLayout.fromString(
      """
/src/main/scala/com/Hello.scala
object Hello // With comment
/src/main/scala/com/Hello2.scala
object Hello2
}
""",
      root = workspace(),
    )
    // Nothing changed, we haven't committed yet.
    assertLsFiles(initialFiles) // Need to stage
    workspace.executeCommand("git add .")
    val finalFiles = initialFiles + "src/main/scala/com/Hello2.scala\n"
    assertLsFiles(finalFiles)
    workspace.executeCommand(
      "git commit --no-gpg-sign --no-verify -m 'Add Hello2.scala'"
    )
    assertLsFiles(finalFiles)
    val oids = lsFilesStage().map(_.oid)
    Using(GitCat.batch(oids.mkString("\n"), cwd = workspace())) { it =>
      val files = it.toList
      assertEquals(files.map(f => f.oid), oids)
      assert(files.forall(f => f.bytes.length > 0))
    }
  }
}
