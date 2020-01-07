package tests

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.mtags
import scala.meta.internal.semver.SemVer
import scala.util.Properties
import scala.meta.io.AbsolutePath
import funsuite.Test

class BaseSuite extends funsuite.FunSuite {
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

  def assertNotEmpty(string: String): Unit = {
    if (string.isEmpty) {
      fail(s"expected non-empty string, obtained empty string.")
    }
  }
  def assertEmpty(string: String): Unit = {
    if (!string.isEmpty) {
      fail(s"expected empty string, obtained: $string")
    }
  }
  def assertContains(string: String, substring: String): Unit = {
    assert(string.contains(substring))
  }
  def assertNotContains(string: String, substring: String): Unit = {
    assert(!string.contains(substring))
  }
  def assertNotEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained == expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> == expected=<$expected>$hintMsg")
    }
  }
  def assertEquals[T](obtained: T, expected: T, hint: String = ""): Unit = {
    if (obtained != expected) {
      val hintMsg = if (hint.isEmpty) "" else s" (hint: $hint)"
      assertNoDiff(obtained.toString, expected.toString, hint)
      fail(s"obtained=<$obtained> != expected=<$expected>$hintMsg")
    }
  }
  def assertIsNotDirectory(path: AbsolutePath): Unit = {
    if (path.isDirectory) {
      fail(s"directory exists: $path")
    }
  }

  def testAsync(
      options: funsuite.TestOptions,
      maxDuration: Duration = Duration("10min")
  )(
      run: => Future[Unit]
  ): Unit = {
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
