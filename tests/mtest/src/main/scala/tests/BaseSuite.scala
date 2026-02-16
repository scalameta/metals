package tests

import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.Duration
import scala.util.Properties

import scala.meta.internal.metals.Testing
import scala.meta.internal.semver.SemVer

import munit.Flaky
import munit.Tag

abstract class BaseSuite extends munit.FunSuite with Assertions {

  /**
   * Tests that are only flaky on Windows
   */
  val FlakyWindows = new Tag("FlakyWindows")

  Testing.enable()

  def isJava11: Boolean =
    Properties.isJavaAtLeast("11")

  def isJava17: Boolean =
    Properties.isJavaAtLeast("17")

  def isJava22: Boolean =
    Properties.isJavaAtLeast("22")

  def isJava21: Boolean =
    Properties.isJavaAtLeast("21")

  def isJava24: Boolean =
    Properties.isJavaAtLeast("24")

  def isJava25: Boolean =
    Properties.isJavaAtLeast("25")

  def isWindows: Boolean =
    Properties.isWin

  def isMacOS: Boolean =
    Properties.isMac

  def userHome: Path = Paths.get(System.getProperty("user.home"))

  def coursierCacheDir: Path =
    if (isWindows) userHome.resolve("AppData/Local/Coursier/Cache")
    else if (isMacOS) userHome.resolve("Library/Caches/Coursier")
    else userHome.resolve(".cache/coursier")

  def isValidScalaVersionForEnv(scalaVersion: String): Boolean =
    SemVer.isCompatibleVersion(
      BaseSuite.minScalaVersionForJDK9OrHigher,
      scalaVersion
    ) || scalaVersion.startsWith("3.")

  override def munitTimeout: Duration = Duration("10min")

  // NOTE(olafur): always ignore flaky test failures.
  override def munitFlakyOK = true

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms ++ List(
      new TestTransform(
        "FlakyWindows",
        test =>
          if (test.tags(FlakyWindows) && Properties.isWin) test.tag(Flaky)
          else test
      ),
      munitFlakyTransform
    )

  val compatProcess: Map[String, String => String] =
    Map.empty[String, String => String]

  def getExpected(
      default: String,
      compat: Map[String, String],
      scalaVersion: String
  ): String = {
    val postProcess = compatProcess
      .collectFirst {
        case (ver, process) if scalaVersion.startsWith(ver) => process
      }
      .getOrElse(identity[String] _)

    val result = compatOrDefault(default, compat, scalaVersion)

    postProcess(result)
  }

  def compatOrDefault[A](
      default: A,
      compat: Map[String, A],
      scalaVersion: String
  ): A =
    Compat
      .forScalaVersion(scalaVersion, compat)
      .getOrElse(default)

  protected def toJsonArray(list: List[String]): String = {
    if (list.isEmpty) "[]"
    else s"[${list.mkString("\"", """", """", "\"")}]"
  }
}

object BaseSuite {
  val minScalaVersionForJDK9OrHigher: String = "2.12.10"
}
