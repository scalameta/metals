package tests

import scala.meta.internal.metals.Fuzzy

import munit.Location

class FilenameLength extends BaseSuite {
  def check(filename: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(expected) {
      val obtained = Fuzzy.nameLength(filename)
      assertNoDiff(obtained.toString, expected.length.toString)
    }
  }

  check("Path.class", "Path")
  check("Polygon$PolygonPathIterator.class", "PolygonPathIterator")
}
