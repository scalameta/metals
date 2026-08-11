package tests

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.UserConfiguration

import munit.Location

class SbtVersionSuite extends BaseSuite {
  def check(
      layout: String,
      expected: String,
  )(implicit loc: Location): Unit = {
    test(expected) {
      val root = FileLayout.fromString(layout)
      val obtained = SbtBuildTool(
        root,
        () => UserConfiguration(),
      ).version
      assertNoDiff(obtained, expected)
    }
  }

  check(
    """
      |/project/build.properties
      |sbt.version=0.13
      """.stripMargin,
    "0.13",
  )

  check(
    """
      |/project/build.properties
      |sbt.version=1.1.3
    """.stripMargin,
    "1.1.3",
  )

  test("loadVersionForPath") {
    val root = FileLayout.fromString(
      """|/project/build.properties
         |sbt.version=2.0.4
         |/build.sbt
         |scalaVersion := "3.3.4"
         |/project/plugins.sbt
         |addSbtPlugin("com.example" % "example" % "1.0")
         |""".stripMargin
    )
    assertEquals(
      SbtBuildTool.loadVersionForPath(root.resolve("build.sbt")),
      Some("2.0.4"),
    )
    assertEquals(
      SbtBuildTool.loadVersionForPath(root.resolve("project/plugins.sbt")),
      Some("2.0.4"),
    )
  }

}
