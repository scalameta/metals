package tests.troubleshoot

import java.nio.file.Files

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JavaInfo
import scala.meta.internal.metals.JdkVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.doctor.DeprecatedRemovedSbtVersion
import scala.meta.internal.metals.doctor.DeprecatedRemovedScalaVersion
import scala.meta.internal.metals.doctor.DeprecatedSbtVersion
import scala.meta.internal.metals.doctor.FutureSbtVersion
import scala.meta.internal.metals.doctor.FutureScalaVersion
import scala.meta.internal.metals.doctor.MissingSourceRoot
import scala.meta.internal.metals.doctor.OutdatedJunitInterfaceVersion
import scala.meta.internal.metals.doctor.OutdatedMunitInterfaceVersion
import scala.meta.internal.metals.doctor.ProblemResolver
import scala.meta.internal.metals.doctor.SemanticDBDisabled
import scala.meta.internal.metals.doctor.UnsupportedSbtVersion
import scala.meta.internal.metals.doctor.UnsupportedScalaVersion
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

  implicit val ctx: ExecutionContext = this.munitExecutionContext

  checkRecommendation(
    "unsupported-scala-version",
    scalaVersion = "2.12.7",
    UnsupportedScalaVersion("2.12.7").message,
  )

  checkRecommendation(
    "deprecated-removed-scala-version",
    scalaVersion = "2.12.9",
    DeprecatedRemovedScalaVersion("2.12.9").message,
  )

  checkRecommendation(
    "future-scala-version",
    scalaVersion = "2.12.50",
    FutureScalaVersion("2.12.50").message,
  )

  checkRecommendation(
    "ok-scala-version",
    scalaVersion = BuildInfo.scala212,
    "",
  )

  checkRecommendation(
    "unsupported-sbt-version",
    scalaVersion = "2.12.7",
    UnsupportedSbtVersion.message,
    sbtVersion = Some("1.2.0"),
  )

  checkRecommendation(
    // we don't have any depracate versions for sbt
    "deprecated-sbt-version",
    scalaVersion = "2.12.14",
    DeprecatedSbtVersion("1.3.0", "2.12.14").message,
    sbtVersion = Some("1.3.0"),
    assume = () =>
      assume(BuildInfo.deprecatedScalaVersions.exists(_.startsWith("2.12"))),
  )

  checkRecommendation(
    "deprecated-removed-sbt-version",
    scalaVersion = "2.12.9",
    DeprecatedRemovedSbtVersion("1.3.0", "2.12.9").message,
    sbtVersion = Some("1.3.0"),
  )

  checkRecommendation(
    "future-sbt-version",
    scalaVersion = "2.12.51",
    FutureSbtVersion.message,
    sbtVersion = Some("1.6.0"),
  )

  checkRecommendation(
    "ok-sbt-version",
    scalaVersion = BuildInfo.scala212,
    "",
    sbtVersion = Some("1.6.0"),
  )

  checkRecommendation(
    "missing-semanticdb",
    scalaVersion = BuildInfo.scala212,
    SemanticDBDisabled(
      BuildInfo.scala212,
      BuildInfo.bloopVersion,
      false,
    ).message,
    scalacOpts = Nil,
  )

  checkRecommendation(
    "missing-sourceroot",
    scalaVersion = BuildInfo.scala212,
    MissingSourceRoot("\"-P:semanticdb:sourceroot:$workspace\"").message,
    scalacOpts = List("-Xplugin:/semanticdb-scalac_2.12.12-4.4.2.jar"),
  )

  checkRecommendation(
    "novocode-junit-interface",
    scalaVersion = BuildInfo.scala213,
    OutdatedJunitInterfaceVersion.message,
    classpath = List("/com/novocode/junit-interface/0.11/"),
  )

  checkRecommendation(
    "github-junit-interface",
    scalaVersion = BuildInfo.scala213,
    OutdatedJunitInterfaceVersion.message,
    classpath = List("/com/github/sbt/junit-interface/0.13.2/"),
  )

  checkRecommendation(
    "github-junit-interface-valid",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List("/com/github/sbt/junit-interface/0.13.3/"),
  )

  checkRecommendation(
    "no-test-explorer-provider",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List("/com/github/sbt/junit-interface/0.13.2/"),
    isTestExplorerProvider = false,
  )

  checkRecommendation(
    "no-test-explorer-for-sbt",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List(
      "org/scalameta/munit_2.13/0.7.29/munit_2.13-0.7.29.jar",
      "/com/github/sbt/junit-interface/0.13.2/",
    ),
    sbtVersion = Some(BuildInfo.sbtVersion),
  )

  checkRecommendation(
    "munit_2.13-0.x",
    scalaVersion = BuildInfo.scala213,
    OutdatedMunitInterfaceVersion.message,
    classpath = List("org/scalameta/munit_2.13/0.7.29/munit_2.13-0.7.29.jar"),
  )

  checkRecommendation(
    "munit_3-0.x",
    scalaVersion = BuildInfo.scala213,
    OutdatedMunitInterfaceVersion.message,
    classpath = List("org/scalameta/munit_3/0.7.29/munit_3-0.7.29.jar"),
  )

  checkRecommendation(
    "munit-1.0.0-M2",
    scalaVersion = BuildInfo.scala213,
    OutdatedMunitInterfaceVersion.message,
    classpath = List("org/scalameta/munit_2.13/1.0.0-M2/"),
  )

  checkRecommendation(
    "munit-valid",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List("org/scalameta/munit_2.13/1.0.0-M3/"),
  )

  checkRecommendation(
    "munit-valid-2",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List("org/scalameta/munit_2.13/1.0.1/"),
  )

  checkRecommendation(
    "main.sc",
    scalaVersion = BuildInfo.scala213,
    "",
    classpath = List("org/scalameta/munit_2.13/1.0.1/"),
  )

  def checkRecommendation(
      name: TestOptions,
      scalaVersion: String,
      expected: String,
      scalacOpts: List[String] = List(
        "-Xplugin:/semanticdb-scalac_2.12.12-4.4.2.jar",
        "-P:semanticdb:sourceroot:/tmp/metals",
      ),
      sbtVersion: Option[String] = None,
      invalidJavaHome: Boolean = false,
      classpath: List[String] = Nil,
      isTestExplorerProvider: Boolean = true,
      assume: () => Unit = () => (),
  )(implicit loc: Location): Unit = {
    test(name) {
      assume()
      val workspace = Files.createTempDirectory("metals")
      workspace.toFile().deleteOnExit()
      val javaHome =
        if (invalidJavaHome)
          Some("/some/invalid/path")
        else
          None // JdkSources will fallback to default java home path

      val javaInfo =
        for {
          home <- javaHome
          version <- JdkVersion.maybeJdkVersionFromJavaHome(
            Some(AbsolutePath(System.getProperty("java.home")))
          )
        } yield JavaInfo(home, version)

      val problemResolver = new ProblemResolver(
        AbsolutePath(workspace),
        new TestMtagsResolver(checkCoursier = false),
        () => None,
        () => isTestExplorerProvider,
        () => javaInfo,
      )

      val target =
        scalaTarget(name.name, scalaVersion, scalacOpts, sbtVersion, classpath)
      val message = problemResolver.recommendation(target).getOrElse("")

      assertNoDiff(
        message,
        expected.replace("$workspace", workspace.toString()),
      )
    }
  }

  def scalaTarget(
      id: String,
      scalaVersion: String,
      scalacOptions: List[String],
      sbtVersion: Option[String] = None,
      classpatch: List[String] = Nil,
  ): ScalaTarget = {
    val scalaBinaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    val buildId = new BuildTargetIdentifier(id)
    val capabilities = new BuildTargetCapabilities()
    capabilities.setCanCompile(true)
    capabilities.setCanDebug(true)
    capabilities.setCanRun(true)
    capabilities.setCanTest(true)
    val buildTarget =
      new BuildTarget(
        buildId,
        /* tags = */ Nil.asJava,
        /* languageIds = */ Nil.asJava,
        /* dependencies = */ Nil.asJava,
        /* capabilities = */ capabilities,
      )
    buildTarget.setDisplayName(id)
    val scalaBuildTarget = new ScalaBuildTarget(
      "org.scala-lang",
      scalaVersion,
      scalaBinaryVersion,
      ScalaPlatform.JVM,
      /* jars = */ Nil.asJava,
    )

    val scalacOptionsItem = new ScalacOptionsItem(
      buildId,
      scalacOptions.asJava,
      classpatch.asJava,
      "",
    )

    ScalaTarget(
      buildTarget,
      scalaBuildTarget,
      scalacOptionsItem,
      autoImports = None,
      sbtVersion,
      None,
    )
  }
}
