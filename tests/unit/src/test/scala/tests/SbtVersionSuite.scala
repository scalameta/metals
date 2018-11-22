package tests

import scala.meta.internal.metals.SbtVersion

object SbtVersionSuite extends BaseSuite {
  def check(
      layout: String,
      expected: String
  ): Unit = {
    test(expected) {
      val root = FileLayout.fromString(layout)
      val obtained = SbtVersion(root).version
      assertNoDiff(obtained, expected)
    }
  }

  check(
    """
      |/project/build.properties
      |sbt.version=0.13
      """.stripMargin,
    "0.13"
  )

  check(
    """
      |/project/build.properties
      |sbt.version=1.1.3
    """.stripMargin,
    "1.1.3"
  )

}
