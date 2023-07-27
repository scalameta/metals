package tests

import scala.util.Failure
import scala.util.Success

import scala.meta.internal.metals.PathMatcher
import scala.meta.internal.metals.ScalafmtConfig
import scala.meta.internal.metals.ScalafmtDialect
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

import com.typesafe.config.ConfigFactory
import munit.TestOptions
import munit.internal.io.PlatformIO

class ScalafmtConfigSuite extends BaseSuite {

  val version: SemVer.Version = SemVer.Version.fromString("3.0.0-RC3")

  checkParseOk(
    s"""|version = 3.0.0-RC3
        |""".stripMargin,
    ScalafmtConfig(Some(version), None, List.empty, Nil, Nil),
  )

  checkParseOk(
    s"""|version = 3.0.0-RC3
        |runner.dialect = scala3
        |""".stripMargin,
    ScalafmtConfig(
      Some(version),
      Some(ScalafmtDialect.Scala3),
      List.empty,
      Nil,
      Nil,
    ),
  )

  checkParseOk(
    s"""|version = 3.0.0-RC3
        |runner.dialect = scala3
        |fileOverride {
        |  "glob:**/scala-3*/**" {
        |    runner.dialect = scala3
        |  }
        |}
        |""".stripMargin,
    ScalafmtConfig(
      Some(version),
      Some(ScalafmtDialect.Scala3),
      fileOverrides(
        "glob:**/scala-3*/**" -> ScalafmtDialect.Scala3
      ),
      Nil,
      Nil,
    ),
  )

  checkParseOk(
    s"""|version = 3.0.0-RC3
        |fileOverride {
        |  "glob:**/scala-3*/**" {
        |    runner.dialect = scala3
        |  }
        |}
        |""".stripMargin,
    ScalafmtConfig(
      Some(version),
      None,
      fileOverrides(
        "glob:**/scala-3*/**" -> ScalafmtDialect.Scala3
      ),
      Nil,
      Nil,
    ),
  )

  checkUpdate(
    "runner-dialect-update",
    s"""|version = 3.0.0-RC3
        |runner.dialect = scala213
        |maxColumn = 100
        |""".stripMargin,
    s"""|version = 3.0.0-RC3
        |runner.dialect = scala3
        |maxColumn = 100
        |""".stripMargin,
    updateRunnerDialect = Some(ScalafmtDialect.Scala3),
  )

  checkUpdate(
    "runner-dialect-append",
    s"""|version = 3.0.0-RC3
        |maxColumn = 100
        |""".stripMargin,
    s"""|version = 3.0.0-RC3
        |maxColumn = 100
        |runner.dialect = scala3
        |""".stripMargin,
    updateRunnerDialect = Some(ScalafmtDialect.Scala3),
  )

  checkUpdate(
    "with-version",
    s"""|version = 2.7.5
        |maxColumn = 100""".stripMargin,
    s"""|version = "3.0.0-RC3"
        |maxColumn = 100
        |runner.dialect = scala3
        |""".stripMargin,
    updateVersion = Some("3.0.0-RC3"),
    updateRunnerDialect = Some(ScalafmtDialect.Scala3),
  )

  checkUpdate(
    "fileOverride",
    s"""|version = 2.7.5
        |maxColumn = 100
        |""".stripMargin,
    s"""|version = "3.0.0-RC3"
        |maxColumn = 100
        |fileOverride {
        |  "glob:**/scala-2/**" {
        |     runner.dialect = scala213
        |  }
        |  "glob:module/src/main/scala/*" {
        |     runner.dialect = scala3
        |  }
        |}
        |""".stripMargin,
    updateVersion = Some("3.0.0-RC3"),
    updateFileOverride = Map(
      "glob:**/scala-2/**" -> ScalafmtDialect.Scala213,
      "glob:module/src/main/scala/*" -> ScalafmtDialect.Scala3,
    ),
  )

  test("v3.2.0") {
    val cfg = ConfigFactory.parseString(
      s"""|version = "3.2.0"
          |runner.dialect = scala3
          |project.layout = StandardConvention
          |fileOverride {
          | "lang:scala-2" = scala211
          |}
          |""".stripMargin
    )
    val config = ScalafmtConfig.parse(cfg).get
    val root = AbsolutePath(PlatformIO.Paths.get("."))

    def assertDialectFor(path: String, dialect: ScalafmtDialect) =
      assertEquals(
        config.overrideFor(root.resolve(path)).orElse(config.runnerDialect),
        Some(dialect),
      )

    assertDialectFor("src/main/scala-2.12/Main.scala", ScalafmtDialect.Scala212)
    assertDialectFor("src/test/scala-3/SomeTest.scala", ScalafmtDialect.Scala3)
    assertDialectFor(
      "src/main/scala-2/src/Main.scala",
      ScalafmtDialect.Scala211,
    )
    assertDialectFor("src/main/scala/dir/Main.scala", ScalafmtDialect.Scala3)
  }

  def checkUpdate(
      options: TestOptions,
      config: String,
      expected: String,
      updateVersion: Option[String] = None,
      updateRunnerDialect: Option[ScalafmtDialect] = None,
      updateFileOverride: Map[String, ScalafmtDialect] = Map.empty,
  ): Unit = {
    test(options) {
      val obtained =
        ScalafmtConfig.update(
          config,
          updateVersion,
          updateRunnerDialect,
          updateFileOverride,
        )
      assertDiffEqual(obtained, expected)
    }
  }

  def checkParseOk(
      config: TestOptions,
      expected: ScalafmtConfig,
  ): Unit = {
    test(config) {
      val cfg = ConfigFactory.parseString(config.name)
      val inspected = ScalafmtConfig.parse(cfg)
      inspected match {
        case Failure(error) => fail(s"inspect failed with error: $error")
        case Success(values) =>
          assertEquals(values, expected)
      }
    }
  }

  private def fileOverrides(
      values: (String, ScalafmtDialect)*
  ): List[(PathMatcher, ScalafmtDialect)] =
    values.map { case (pattern, dialect) =>
      PathMatcher.Nio(pattern) -> dialect
    }.toList

}
