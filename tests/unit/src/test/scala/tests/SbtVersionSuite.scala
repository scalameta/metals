package tests

import scala.util.Properties

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

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

  List("1.5.8", "1.13.1", "2.1.0", "2.1.1", "2.10.0").foreach { version =>
    checkMetalsPlugin(Some(version), BuildInfo.metalsVersion)
  }

  List("2.0.0-RC11", "2.0.0", "2.0.12", "2.1.0-M1", "2.1.0-RC1")
    .foreach { version =>
      checkMetalsPlugin(Some(version), BuildInfo.sbtMetalsForSbt20)
    }

  checkMetalsPlugin(None, BuildInfo.metalsVersion)

  test("metals-plugin-upgrade-from-sbt-2.0") {
    val root = FileLayout.fromString(
      """|/project/build.properties
         |sbt.version=2.0.12
         |""".stripMargin
    )
    assert(SbtBuildTool.writeSbtMetalsPlugins(root))
    assertMetalsPluginVersion(root, BuildInfo.sbtMetalsForSbt20)

    root.resolve("project/build.properties").writeText("sbt.version=2.1.0\n")
    assert(SbtBuildTool.writeSbtMetalsPlugins(root))
    assertMetalsPluginVersion(root, BuildInfo.metalsVersion)
    assert(!SbtBuildTool.writeSbtMetalsPlugins(root))
  }

  private def checkMetalsPlugin(
      sbtVersion: Option[String],
      expectedVersion: String,
  ): Unit = {
    test(s"metals-plugin-${sbtVersion.getOrElse("unknown-sbt-version")}") {
      val layout = sbtVersion
        .map(version => s"/project/build.properties\nsbt.version=$version\n")
        .getOrElse("")
      val root = FileLayout.fromString(layout)
      assert(SbtBuildTool.writeSbtMetalsPlugins(root))
      assertMetalsPluginVersion(root, expectedVersion)
      assert(!SbtBuildTool.writeSbtMetalsPlugins(root))
    }
  }

  private def assertMetalsPluginVersion(
      root: AbsolutePath,
      expectedVersion: String,
  ): Unit = {
    val expected =
      s"""addSbtPlugin("org.scalameta" % "sbt-metals" % "$expectedVersion")"""
    List("project/metals.sbt", "project/project/metals.sbt").foreach { path =>
      val contents = root.resolve(path).readText
      assert(contents.linesIterator.contains(expected), contents)
      assertEquals(
        contents.contains(
          "https://central.sonatype.com/repository/maven-snapshots"
        ),
        expectedVersion.contains("SNAPSHOT"),
      )
    }
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
