package tests

import munit.Location
import scala.meta.io.AbsolutePath

trait Assertions extends munit.Assertions {

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
  )(implicit loc: Location): Unit = {
    if (obtained == expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> == expected=<$expected>$hintMsg")
    }
  }

  def assertDiffEqual[T](obtained: T, expected: T, hint: String = "")(
      implicit loc: Location
  ): Unit = {
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
