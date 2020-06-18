package tests.pants

import java.nio.file.Files

import scala.meta.internal.pantsbuild.PantsGlobs

import tests.BaseSuite

class PantsGlobsSuite extends BaseSuite {
  def check(
      name: String,
      includes: List[String],
      expectedWalkDepth: Option[Int],
      expectedIncludes: List[String]
  )(implicit
      loc: munit.Location
  ): Unit = {
    test(name) {
      val workspace = Files.createTempDirectory("metals")
      Files.delete(workspace)
      val baseDirectory = workspace.resolve("src")
      val obtained =
        PantsGlobs(includes, Nil).bloopConfig(workspace, baseDirectory)
      assertEquals(obtained.includes, expectedIncludes)
      assertEquals(obtained.walkDepth, expectedWalkDepth)
    }
  }

  check(
    "basic",
    List("src/*.scala"),
    expectedWalkDepth = Some(1),
    expectedIncludes = List("glob:*.scala")
  )

  check(
    "recursive",
    List("src/**/*.scala"),
    expectedWalkDepth = None,
    expectedIncludes = List("glob:**.scala")
  )

  check(
    "nested-directory",
    List("src/foo/*.scala"),
    expectedWalkDepth = Some(2),
    expectedIncludes = List("glob:foo/*.scala")
  )

  check(
    "nested-directory-with-recursive",
    List("src/foo/**/*.scala"),
    expectedWalkDepth = None,
    expectedIncludes = List("glob:foo/**.scala")
  )

  check(
    "max",
    List("src/*.scala", "src/foo/*.scala", "src/foo/bar/*.scala"),
    expectedWalkDepth = Some(3),
    expectedIncludes = List(
      "glob:*.scala",
      "glob:foo/*.scala",
      "glob:foo/bar/*.scala"
    )
  )
}
