package tests

import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.TextEdit
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.semver.SemVer
import scala.util.Properties
import munit.Flaky
import munit.Tag

class BaseSuite extends munit.FunSuite with Assertions {

  /** Tests that are only flaky on Windows */
  val FlakyWindows = new Tag("FlakyWindows")

  def isJava8: Boolean =
    !Properties.isJavaAtLeast("9")

  def isWindows: Boolean =
    Properties.isWin

  def isValidScalaVersionForEnv(scalaVersion: String): Boolean =
    this.isJava8 || SemVer.isCompatibleVersion(
      BaseSuite.minScalaVersionForJDK9OrHigher,
      scalaVersion
    )

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

  private def scalaBinary(scalaVersion: String): String =
    scalaVersion.split("\\.").take(2).mkString(".")

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

    val result = compat
      .collect { case (ver, code) if scalaVersion.startsWith(ver) => code }
      .headOption
      .getOrElse(default)

    postProcess(result)
  }

  def locationToCode(code: String, uri: String, offsetRange: l.Range, location: l.Location): List[TextEdit] = {
    if (location.getUri == uri) {
      List(
        new l.TextEdit(
          new l.Range(
            location.getRange.getStart,
            location.getRange.getStart
          ),
          "<<"
        ),
        new l.TextEdit(
          new l.Range(
            location.getRange.getEnd,
            location.getRange.getEnd
          ),
          ">>"
        )
      )
    } else {
      val filename = location.getUri
      val comment = s"/*$filename*/"
      if (code.contains(comment)) {
        Nil
      } else {
        List(new l.TextEdit(offsetRange, comment))
      }
    }
  }
}

object BaseSuite {
  val minScalaVersionForJDK9OrHigher: String = "2.12.10"
}
