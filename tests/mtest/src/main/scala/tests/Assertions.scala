package tests

import funsuite.Location
import scala.meta.io.AbsolutePath
import scala.collection.JavaConverters._

trait Assertions extends funsuite.Assertions {

  def unifiedDiff(expected: String, obtained: String): String = {
    def splitIntoLines(string: String): Seq[String] =
      string.trim.replace("\r\n", "\n").split("\n")

    val references = splitIntoLines(expected).asJava
    val definition = splitIntoLines(obtained).asJava
    val diff = difflib.DiffUtils.diff(references, definition)
    difflib.DiffUtils
      .generateUnifiedDiff(
        "references",
        "definition",
        references,
        diff,
        1
      )
      .asScala
      .iterator
      .filterNot(_.startsWith("@@"))
      .mkString("\n")
  }

  def assertNoDiffOrPrintObtained(
      obtained: String,
      expected: String
  )(implicit loc: Location): Unit = {
    // FIXME(gabro): surface this in funsuite Assertions
    funsuite.internal.Diffs.assertNoDiffOrPrintExpected(obtained, expected)
  }

  def assertNotEmpty(string: String)(implicit loc: Location): Unit = {
    if (string.isEmpty) {
      fail(s"expected non-empty string, obtained empty string.")
    }
  }

  def assertEmpty(string: String)(implicit loc: Location): Unit = {
    if (!string.isEmpty) {
      fail(s"expected empty string, obtained: $string")
    }
  }

  def assertContains(string: String, substring: String)(
      implicit loc: Location
  ): Unit = {
    assert(string.contains(substring))
  }

  def assertNotContains(string: String, substring: String)(
      implicit loc: Location
  ): Unit = {
    assert(!string.contains(substring))
  }

  def assertDiffNotEqual[T](
      obtained: T,
      expected: T,
      hint: String = ""
  ): Unit = {
    if (obtained == expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> == expected=<$expected>$hintMsg")
    }
  }

  def assertDiffEqual[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
    }
  }

  def assertIsNotDirectory(path: AbsolutePath)(implicit loc: Location): Unit = {
    if (path.isDirectory) {
      fail(s"directory exists: $path")
    }
  }

}

object Assertions extends Assertions
