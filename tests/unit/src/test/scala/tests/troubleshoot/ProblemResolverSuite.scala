package tests.troubleshoot

import java.nio.file.Files

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ScalaTarget
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.troubleshoot.DeprecatedSbtVersion
import scala.meta.internal.troubleshoot.DeprecatedScalaVersion
import scala.meta.internal.troubleshoot.FutureSbtVersion
import scala.meta.internal.troubleshoot.FutureScalaVersion
import scala.meta.internal.troubleshoot.MissingJdkSources
import scala.meta.internal.troubleshoot.MissingSourceRoot
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
    isSbt = true
  )

  checkRecommendation(
    "deprecated-sbt-version",
    scalaVersion = "2.12.8",
    DeprecatedSbtVersion.message,
    isSbt = true
  )

  checkRecommendation(
    "future-sbt-version",
    scalaVersion = "2.12.51",
    FutureSbtVersion.message,
    isSbt = true
  )

  checkRecommendation(
    "ok-sbt-version",
    scalaVersion = BuildInfo.scala212,
    "",
    isSbt = true
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
    MissingJdkSources.message,
    isSbt = true,
    invalidJavaHome = true
  )

  def checkRecommendation(
      name: TestOptions,
      scalaVersion: String,
      expected: String,
      scalacOpts: List[String] = List(
        "-Xplugin:/semanticdb-scalac_2.12.12-4.4.2.jar",
        "-P:semanticdb:sourceroot:/tmp/metals"
      ),
      isSbt: Boolean = false,
      invalidJavaHome: Boolean = false
  ): Unit = {
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

      val target = scalaTarget(name.name, scalaVersion, scalacOpts, isSbt)
      val message = problemResolver.recommendation(target)

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
      isSbt: Boolean = false
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
      /* classpath = */ Nil.asJava,
      ""
    )

    ScalaTarget(
      buildTarget,
      scalaBuildTarget,
      scalacOptionsItem,
      autoImports = None,
      isSbt = isSbt
    )
  }
}
