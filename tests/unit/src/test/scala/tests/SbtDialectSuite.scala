package tests

import java.nio.file.Paths

import scala.meta._
import scala.meta.dialects
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform
import ch.epfl.scala.bsp4j.ScalacOptionsItem

class SbtDialectSuite extends BaseSuite {

  private val significantIndentSbt =
    """|val x = "3" match
       |  case "3" => 1
       |  case _   => 0
       |""".stripMargin

  private val bracedSbt =
    """|val x = "3" match {
       |  case "3" => 1
       |  case _   => 0
       |}
       |""".stripMargin

  private def parseOk(code: String, dialect: Dialect): Boolean =
    Input.String(code).safeParse[Source](dialect).toOption.isDefined

  private def emptySelector(): ScalaVersionSelector =
    new ScalaVersionSelector(() => UserConfiguration(), BuildTargets.empty)

  private def sbtMetaTarget(
      scalaVersion: String,
      sbtVersion: String,
  ): ScalaTarget = {
    val id = new BuildTargetIdentifier("sbt-meta")
    val capabilities = new BuildTargetCapabilities()
    val info = new BuildTarget(
      id,
      Nil.asJava,
      Nil.asJava,
      Nil.asJava,
      capabilities,
    )
    info.setDisplayName("sbt-meta")
    info.setDataKind("sbt")
    val scalaInfo = new ScalaBuildTarget(
      "org.scala-lang",
      scalaVersion,
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion),
      ScalaPlatform.JVM,
      Nil.asJava,
    )
    val scalac = new ScalacOptionsItem(id, Nil.asJava, Nil.asJava, "")
    ScalaTarget(
      info,
      scalaInfo,
      scalac,
      autoImports = None,
      sbtVersion = Some(sbtVersion),
      bspConnection = None,
    )
  }

  test("dialectForSbtVersion-fallback") {
    assertEquals(
      ScalaVersions.dialectForSbtVersion(Some("1.11.2")),
      dialects.Sbt,
    )
    assertEquals(
      ScalaVersions.dialectForSbtVersion(None),
      dialects.Sbt,
    )
    val sbt2 = ScalaVersions.dialectForSbtVersion(Some("2.0.4"))
    assert(sbt2.allowSignificantIndentation)
    assert(sbt2.allowToplevelTerms)
  }

  test("fallback-getDialect-sbt2-parses-significant-indentation") {
    val root = FileLayout.fromString(
      s"""|/project/build.properties
         |sbt.version=2.0.4
         |/build.sbt
         |$significantIndentSbt
         |""".stripMargin
    )
    val path = root.resolve("build.sbt")
    val dialect = emptySelector().getDialect(path)
    assert(dialect.allowSignificantIndentation)
    assert(dialect.allowToplevelTerms)
    assert(parseOk(path.readText, dialect))
  }

  test("fallback-getDialect-sbt1-rejects-significant-indentation") {
    val root = FileLayout.fromString(
      s"""|/project/build.properties
         |sbt.version=1.11.2
         |/build.sbt
         |$significantIndentSbt
         |""".stripMargin
    )
    val path = root.resolve("build.sbt")
    val dialect = emptySelector().getDialect(path)
    assertEquals(dialect, dialects.Sbt)
    assert(!dialect.allowSignificantIndentation)
    assert(!parseOk(path.readText, dialect))
    assert(parseOk(bracedSbt, dialect))
  }

  test("fallback-plugins.sbt-uses-workspace-sbt-version") {
    val root = FileLayout.fromString(
      """|/project/build.properties
         |sbt.version=2.0.4
         |/build.sbt
         |val x = 1
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
    val dialect =
      emptySelector().getDialect(root.resolve("project/plugins.sbt"))
    assert(dialect.allowSignificantIndentation)
  }

  test("build-target-sbt2-uses-scala-dialect-with-toplevel-terms") {
    val target = sbtMetaTarget(V.scala3, "2.0.4")
    val path = AbsolutePath(Paths.get("/workspace/build.sbt"))
    val dialect = target.dialect(path)
    assert(dialect.allowSignificantIndentation)
    assert(dialect.allowToplevelTerms)
    assert(parseOk(significantIndentSbt, dialect))
    assert(parseOk(bracedSbt, dialect))
  }

  test("build-target-sbt1-uses-scala-dialect-with-toplevel-terms") {
    val target = sbtMetaTarget(V.scala212, "1.11.2")
    val path = AbsolutePath(Paths.get("/workspace/build.sbt"))
    val dialect = target.dialect(path)
    assert(dialect.allowToplevelTerms)
    assert(!dialect.allowSignificantIndentation)
    assert(!parseOk(significantIndentSbt, dialect))
    assert(parseOk(bracedSbt, dialect))
  }

  test("build-target-non-sbt-path-keeps-scala-dialect") {
    val target = sbtMetaTarget(V.scala3, "2.0.4")
    val path = AbsolutePath(Paths.get("/workspace/project/Deps.scala"))
    val dialect = target.dialect(path)
    assert(dialect.allowSignificantIndentation)
    assert(!dialect.allowToplevelTerms)
  }

}
