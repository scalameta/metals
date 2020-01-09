package tests

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.mtags
import scala.meta.internal.semver.SemVer
import scala.util.Properties
import funsuite.Test
import funsuite.TestOptions
import funsuite.Location

class BaseSuite extends funsuite.FunSuite with Assertions {
  def isJava8: Boolean =
    !Properties.isJavaAtLeast("9")

  def isScala211: Boolean =
    mtags.BuildInfo.scalaCompilerVersion.startsWith("2.11")

  def hasJdkSources: Boolean = JdkSources().isDefined

  def isWindows: Boolean =
    Properties.isWin

  def isValidScalaVersionForEnv(scalaVersion: String): Boolean =
    this.isJava8 || SemVer.isCompatibleVersion(
      BaseSuite.minScalaVersionForJDK9OrHigher,
      scalaVersion
    )

  def skipSuite: Boolean = false

  override def funsuiteTests(): Seq[Test] = {
    if (skipSuite) Seq.empty
    else super.funsuiteTests()
  }

  def testAsync(
      options: TestOptions,
      maxDuration: Duration = Duration("10min")
  )(
      run: => Future[Unit]
  )(implicit loc: Location): Unit = {
    test(options) {
      val fut = run
      Await.result(fut, maxDuration)
    }
  }

  private def scalaVersion: String =
    Properties.versionNumberString

  private def scalaBinary(scalaVersion: String): String =
    scalaVersion.split("\\.").take(2).mkString(".")

  val compatProcess: Map[String, String => String] =
    Map.empty[String, String => String]

  def getExpected(
      default: String,
      compat: Map[String, String],
      scalaVersion: String = this.scalaVersion
  ): String = {
    val postProcess = compatProcess
      .get(scalaBinary(scalaVersion))
      .orElse(compatProcess.get(scalaVersion))
      .getOrElse(identity[String] _)

    val result = compat
      .get(scalaBinary(scalaVersion))
      .orElse(compat.get(scalaVersion))
      .getOrElse(default)

    postProcess(result)
  }
}

object BaseSuite {
  val minScalaVersionForJDK9OrHigher: String = "2.12.10"
}
