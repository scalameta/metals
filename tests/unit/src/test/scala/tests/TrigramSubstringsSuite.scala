package tests

import scala.meta.internal.metals.TrigramSubstrings

import munit.Location

class TrigramSubstringsSuite extends BaseSuite {
  def check(original: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(original) {
      val obtained =
        TrigramSubstrings.seq(original.split('.').toList).mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }

  def checkUppercase(original: String, expected: String)(implicit
      loc: Location
  ): Unit = {
    test(original) {
      val uppercased = TrigramSubstrings.uppercased(original).toList
      val obtained = uppercased.mkString("\n")
      assertNoDiff(obtained, expected)
    }
  }

  check(
    "a.bcde",
    """
      |bcd
      |bce
      |bde
      |cde
      |abc
      |abd
      |abe
      |acd
      |ace
      |ade
      |""".stripMargin,
  )

  checkUppercase(
    "fsmbu",
    """|FSmbu
       |FsMbu
       |FsmBu
       |FsmbU
       |FSMbu
       |FSmBu
       |FSmbU
       |FsMBu
       |FsMbU
       |FsmBU
       |""".stripMargin,
  )

}
