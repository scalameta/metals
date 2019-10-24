package tests

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.RecursivelyDelete

trait BaseDigestSuite extends BaseSuite {

  def digestCurrent(root: AbsolutePath): Option[String]

  def checkSame(
      name: String,
      layout: String,
      altLayout: String
  )(implicit file: sourcecode.File, line: sourcecode.Line) =
    check(name, layout, altLayout, isEqual = true)

  def checkDiff(
      name: String,
      layout: String,
      altLayout: String
  )(implicit file: sourcecode.File, line: sourcecode.Line) =
    check(name, layout, altLayout, isEqual = false)

  private def check(
      name: String,
      layout: String,
      altLayout: String,
      isEqual: Boolean = true
  )(implicit file: sourcecode.File, line: sourcecode.Line): Unit = {
    test(name) {
      val root = FileLayout.fromString(layout)
      val altRoot = FileLayout.fromString(altLayout)
      assertEquals(
        digestCurrent(root),
        digestCurrent(root),
        "First layout should be equal when run twice"
      )
      assertEquals(
        digestCurrent(altRoot),
        digestCurrent(altRoot),
        "Second layout should be equal when run twice"
      )
      val rootDigest = digestCurrent(root)
      RecursivelyDelete(root)
      val altDigest = digestCurrent(FileLayout.fromString(altLayout))
      (rootDigest, altDigest) match {
        case (None, None) => ()
        case (Some(x), Some(y)) =>
          if (isEqual) {
            assertNoDiff(x, y)
          } else {
            assertNotEquals(x, y, "The digests should not be equal")
          }
        case (None, Some(y)) =>
          fail(s"expected digest $y but did not obtain a digest")
        case (Some(x), None) =>
          fail(s"expected no digest but did obtained digest $x")
      }
    }
  }

}
