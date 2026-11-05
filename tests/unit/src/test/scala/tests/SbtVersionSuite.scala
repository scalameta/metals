package tests

import scala.util.Properties

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

  test("embedded-sbt-1-runner") {
    checkEmbeddedRunner(Some("1.13.1"), "1")
  }

  test("embedded-sbt-2-runner") {
    checkEmbeddedRunner(Some("2.0.11"), "2")
  }

  private def checkEmbeddedRunner(
      version: Option[String],
      series: String,
  ): Unit = {
    val root = FileLayout.fromString(
      s"""|/project/build.properties
          |sbt.version=${version.getOrElse("1.13.1")}
          |""".stripMargin
    )
    val launcher =
      SbtBuildTool(version, root, () => UserConfiguration())
        .embeddedSbtLauncher()
    val path = launcher.toNIO
    val scriptName = if (Properties.isWin) "sbt.bat" else "sbt"
    assertEquals(path.getFileName.toString, scriptName)
    assertEquals(path.getParent.getFileName.toString, "bin")
    assertEquals(path.getParent.getParent.getFileName.toString, series)
    assert(path.getParent.resolve("sbt-launch.jar").toFile.isFile)
    assert(path.toFile.canExecute)
  }

}
