package tests.pants

import tests.BaseSuite
import scala.meta.internal.io.PathIO
import scala.meta.internal.pantsbuild.PantsConfiguration

object PantsSuite extends BaseSuite {
  def checkSourceRoots(
      name: String,
      original: String,
      expected: String
  ): Unit = {
    test(name) {
      val targets = original.trim.linesIterator.toList
      val obtained = PantsConfiguration
        .sourceRoots(PathIO.workingDirectory, targets)
        .map(_.toRelative(PathIO.workingDirectory).toURI(true))
        .mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }
  checkSourceRoots(
    "two",
    """
      |a::
      |b::
      |""".stripMargin,
    """
      |a/
      |b/
      |""".stripMargin
  )

  checkSourceRoots(
    "nested",
    """
      |a/b/c::
      |a::
      |a/b::
      |""".stripMargin,
    """
      |a/
      |""".stripMargin
  )

  checkSourceRoots(
    "specific-target",
    """
      |a/b:b
      |a/c
      |""".stripMargin,
    """
      |a/b/
      |a/c/
      |""".stripMargin
  )

  checkSourceRoots(
    "slash-colon",
    """
      |a/::
      |""".stripMargin,
    """
      |a/
      |""".stripMargin
  )
}
