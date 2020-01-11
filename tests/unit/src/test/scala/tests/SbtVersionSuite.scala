package tests

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.MetalsServerConfig
import munit.Location

class SbtVersionSuite extends BaseSuite {
  def check(
      layout: String,
      expected: String
  )(implicit loc: Location): Unit = {
    test(expected) {
      val root = FileLayout.fromString(layout)
      val obtained = SbtBuildTool(
        root,
        () => UserConfiguration(),
        MetalsServerConfig.default
      ).version
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
