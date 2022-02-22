package tests.troubleshoot

import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.troubleshoot.DeprecatedSbtVersion
import scala.meta.internal.troubleshoot.DeprecatedScalaVersion
import scala.meta.internal.troubleshoot.FutureSbtVersion
import scala.meta.internal.troubleshoot.FutureScalaVersion
import scala.meta.internal.troubleshoot.MissingJdkSources
import scala.meta.internal.troubleshoot.MissingSourceRoot
import scala.meta.internal.troubleshoot.OutdatedJunitInterfaceVersion
import scala.meta.internal.troubleshoot.ProblemResolver
import scala.meta.internal.troubleshoot.SemanticDBDisabled
import scala.meta.internal.troubleshoot.UnsupportedSbtVersion
import scala.meta.internal.troubleshoot.UnsupportedScalaVersion
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalaPlatform
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import munit.FunSuite
import munit.Location
import munit.TestOptions
import tests.TestMtagsResolver

class ProblemResolverSuite extends FunSuite {

  checkRecommendation(
    "unsupported-scala-version",
    scalaVersion = "2.12.7",
    UnsupportedScalaVersion("2.12.7").message
  )

  checkRecommendation(
    "deprecated-scala-version",
    scalaVersion = "2.12.8",
    DeprecatedScalaVersion("2.12.8").message
  )

  checkRecommendation(
    "future-scala-version",
    scalaVersion = "2.12.50",
    FutureScalaVersion("2.12.50").message
  )

  checkRecommendation(
    "ok-scala-version",
    scalaVersion = BuildInfo.scala212,
    ""
  )

  checkRecommendation(
    "unsupported-sbt-version",
    scalaVersion = "2.12.7",
    UnsupportedSbtVersion.message,
    sbtVersion = Some("1.2.0")
  )

  checkRecommendation(
    "deprecated-sbt-version",
    scalaVersion = "2.12.8",
    DeprecatedSbtVersion.message,
    sbtVersion = Some("1.3.0")
  )

  checkRecommendation(
    "future-sbt-version",
    scalaVersion = "2.12.51",
    FutureSbtVersion.message,
    sbtVersion = Some("1.6.0")
  )

  checkRecommendation(
    "ok-sbt-version",
    scalaVersion = BuildInfo.scala212,
    "",
    sbtVersion = Some("1.6.0")
  )

  checkRecommendation(
    "missing-semanticdb",
    scalaVersion = BuildInfo.scala212,
    SemanticDBDisabled(
      BuildInfo.scala212,
      BuildInfo.bloopVersion,
      false
    ).message,
    scalacOpts = Nil
  )

  checkRecommendation(
    "missing-sourceroot",
    scalaVersion = BuildInfo.scala212,
    MissingSourceRoot("\"-P:semanticdb:sourceroot:$workspace\"").message,
    scalacOpts = List("-Xplugin:/semanticdb-scalac_2.12.12-4.4.2.jar")
  )

  checkRecommendation(
    "missing-jdk-sources",
    scalaVersion = BuildInfo.scala212,
    MissingJdkSources(
      List(
        "/some/invalid/src.zip",
        "/some/invalid/lib/src.zip",
        "/some/invalid/path/src.zip",
        "/some/invalid/path/lib/src.zip"
      ).map(path => AbsolutePath(Paths.get(path)))
    ).message,
    sbtVersion = Some("1.6.0"),
    invalidJavaHome = true
  )

  checkRecommendation(
    "novocode-junit-interface".only,
    scalaVersion = BuildInfo.scala213,
    OutdatedJunitInterfaceVersion.message,
    classpatch = List("/com/novocode/junit-interface/0.11/")
  )

  checkRecommendation(
    "github-junit-interface".only,
    scalaVersion = BuildInfo.scala213,
    OutdatedJunitInterfaceVersion.message,
    classpatch = List("/com/github/sbt/junit-interface/0.13.2/")
  )

  checkRecommendation(
    "github-junit-interface-valid".only,
    scalaVersion = BuildInfo.scala213,
    "",
    classpatch = List("/com/github/sbt/junit-interface/0.13.3/")
  )

  def checkRecommendation(
      name: TestOptions,
      scalaVersion: String,
      expected: String,
      scalacOpts: List[String] = List(
        "-Xplugin:/semanticdb-scalac_2.12.12-4.4.2.jar",
        "-P:semanticdb:sourceroot:/tmp/metals"
      ),
      sbtVersion: Option[String] = None,
      invalidJavaHome: Boolean = false,
      classpatch: List[String] = Nil
  )(implicit loc: Location): Unit = {
    test(name) {
      val workspace = Files.createTempDirectory("metals")
      workspace.toFile().deleteOnExit()
      val javaHome =
        if (invalidJavaHome)
          Some("/some/invalid/path")
        else
          None // JdkSources will fallback to default java home path

      val problemResolver = new ProblemResolver(
        AbsolutePath(workspace),
        new TestMtagsResolver,
        () => None,
        () => javaHome
      )

      val target =
        scalaTarget(name.name, scalaVersion, scalacOpts, sbtVersion, classpatch)
      val message = problemResolver.recommendation(target).getOrElse("")

      assertNoDiff(
        message,
        expected.replace("$workspace", workspace.toString())
      )
    }
  }

  def scalaTarget(
      id: String,
      scalaVersion: String,
      scalacOptions: List[String],
      sbtVersion: Option[String] = None,
      classpatch: List[String] = Nil
  ): ScalaTarget = {
    val scalaBinaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    val buildId = new BuildTargetIdentifier(id)
    val buildTarget =
      new BuildTarget(
        buildId,
        /* tags = */ Nil.asJava,
        /* languageIds = */ Nil.asJava,
        /* dependencies = */ Nil.asJava,
        /* capabilities = */ new BuildTargetCapabilities(true, true, true)
      )
    val scalaBuildTarget = new ScalaBuildTarget(
      "org.scala-lang",
      scalaVersion,
      scalaBinaryVersion,
      ScalaPlatform.JVM,
      /* jars = */ Nil.asJava
    )

    val scalacOptionsItem = new ScalacOptionsItem(
      buildId,
      scalacOptions.asJava,
      classpatch.asJava,
      ""
    )

    ScalaTarget(
      buildTarget,
      scalaBuildTarget,
      scalacOptionsItem,
      autoImports = None,
      sbtVersion
    )
  }
}
