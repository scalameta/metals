package tests

import java.nio.file.Files

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

/**
 * Helper trait for creating test workspaces without full LSP testing infrastructure.
 * {{{
 *   class MySuite extends BaseSuite with WorkspaceHelper {
 *     def suiteName: String = "my-suite"
 *     test("my test") {
 *       val workspace = createWorkspace("test-name")
 *     }
 *   }
 * }}}
 */
trait WorkspaceHelper { self: munit.FunSuite =>

  def suiteName: String

  protected val changeSpacesToDash: Boolean = true

  /** Creates workspace at target/e2e/{suiteName}/{name} */
  protected def createWorkspace(name: String): AbsolutePath = {
    val pathToSuite = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve(suiteName)

    val path =
      if (changeSpacesToDash)
        pathToSuite.resolve(name.replace(' ', '-'))
      else
        pathToSuite.resolve(name)

    Files.createDirectories(path.toNIO)
    path
  }
}
