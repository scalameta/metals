package tests.bazel

import java.nio.file.Files

import scala.meta.internal.builds.ScalaRulesetFinderHeuristic
import scala.meta.io.AbsolutePath

import tests.BaseSuite
import tests.BuildInfo

class ScalaRulesetFinderHeuristicTest extends BaseSuite {

  test("workspace file patterns") {
    val tmp = AbsolutePath(Files.createTempDirectory("workspace file patterns"))

    copyFiles(tmp)("example.WORKSPACE.bazel" -> "WORKSPACE.bazel")
    val finder = ScalaRulesetFinderHeuristic(tmp)
    assertNoDiff(finder.guessRulesetName().get, "dummy-scala_rules")
  }

  test("module file patterns") {
    val tmp = AbsolutePath(Files.createTempDirectory("module file patterns"))

    copyFiles(tmp)(
      "example.WORKSPACE.bazel" -> "WORKSPACE.bazel",
      "example.MODULE.bazel" -> "MODULE.bazel",
    )
    val finder = ScalaRulesetFinderHeuristic(tmp)
    assertNoDiff(finder.guessRulesetName().get, "dummy-scala_rules.2")
  }

  private def copyFiles(
      workDir: AbsolutePath
  )(mappings: (String, String)*): Unit = {
    val testResources =
      AbsolutePath(BuildInfo.testResourceDirectory).resolve("bazel")
    mappings.foreach { case (from, to) =>
      Files.copy(testResources.resolve(from).toNIO, workDir.resolve(to).toNIO)
    }
  }

}
