package tests.metals

import scala.meta.internal.metals.Fuzzy

object FilenameLength extends BaseSuite {
  def check(filename: String, expected: String): Unit = {
    test(expected) {
      val obtained = Fuzzy.nameLength(filename)
      assertNoDiff(obtained.toString, expected.length.toString)
    }
  }

  check("Path.class", "Path")
  check("Polygon$PolygonPathIterator.class", "PolygonPathIterator")
}
