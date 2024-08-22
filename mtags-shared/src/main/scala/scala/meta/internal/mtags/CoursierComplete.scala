package scala.meta.internal.mtags

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.matching.Regex

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.semver.SemVer.Version

import coursierapi.Complete

class CoursierComplete(scalaVersion: String) {
  lazy val api: Complete = coursierapi.Complete
    .create()
    .withScalaVersion(scalaVersion)
    .withScalaBinaryVersion(
      if (scalaVersion.startsWith("3")) "3"
      else scalaVersion.split('.').take(2).mkString(".")
    )

  private def completions(s: String): List[String] = {
    val futureCompletions = Future {
      api.withInput(s).complete().getCompletions().asScala.toList
    }
    try Await.result(futureCompletions, 10.seconds)
    catch {
      case _: Throwable => Nil
    }
  }

  /**
   * Returns a list of completions for the given dependency string.
   *
   * @param dependency The dependency string to complete
   * @param includeScala Include dependencies without specified scala version
   * @param supportNonJvm Default to `::` before version if dependency name doesn't end with `sjs` or `native`
   */
  def complete(
      dependency: String,
      includeScala: Boolean = true,
      supportNonJvm: Boolean = true
  ): List[String] = {
    // Version completions
    if (dependency.replaceAll(":+", ":").count(_ == ':') == 2) {
      versionCompletions(dependency, supportNonJvm)
    } else {
      val javaCompletions = completions(dependency)
      val scalaCompletions =
        if (
          includeScala &&
          dependency.endsWith(":") && dependency.count(_ == ':') == 1
        )
          completions(dependency + ":").map(":" + _)
        else List.empty

      (scalaCompletions ++ javaCompletions).distinct
    }
  }

  private def versionCompletions(
      dependency: String,
      supportNonJvm: Boolean
  ): List[String] = {
    val adjusted = adjustDoubleColon(dependency)
    val sortedCompletions = completions(adjusted).sortWith(
      Version.fromString(_) >= Version.fromString(_)
    )

    def addDoubleColon: Boolean =
      supportNonJvm && !hasSpecifiedPlatform(dependency) &&
        dependency.count(_ == ':') == 3

    // If dependency name doesn't end with `sjs` or `native` and we can default to `::` before version
    if (addDoubleColon)
      sortedCompletions.map(":" + _)
    else sortedCompletions

  }

  private def adjustDoubleColon(dependency: String): String = {
    val doubleColon = dependency.lastIndexOf("::")
    val firstColon = dependency.indexOf("::")
    if (doubleColon > firstColon) {
      dependency.take(doubleColon) + dependency.drop(doubleColon + 1)
    } else dependency
  }

  private def hasSpecifiedPlatform(dependency: String): Boolean = {
    val firstColon = dependency.indexOf(":")
    val lastColon = dependency.lastIndexOf(":")
    val withoutVersion = dependency
      .substring(firstColon, lastColon)
    withoutVersion.contains("_sjs") || withoutVersion.contains("_native")
  }
}

object CoursierComplete {

  def inferEditRange(point: Int, text: String): (Int, Int) = {
    def isArtifactPart(c: Char): Boolean =
      (c == '$') || c.isUnicodeIdentifierPart || c == '.' || c == '-'

    val editStart = {
      var i = point - 1
      while (i >= 0 && isArtifactPart(text.charAt(i))) { i -= 1 }
      i + 1
    }
    val editEnd = {
      var i = point
      val textLen = text.length()
      while (i < textLen && isArtifactPart(text.charAt(i))) { i += 1 }
      i
    }
    (editStart, editEnd)
  }

  val reg: Regex = """//>\s*using\s+(?:test\.)?(lib|plugin|dep)s?\s+"?(.*)""".r
  def isScalaCliDep(line: String): Option[String] = {
    line match {
      case reg(_, deps) =>
        val dep =
          deps.split(",").last
        if (dep.endsWith("\"") || dep.endsWith(" ")) None
        else Some(dep.trim.stripPrefix("\""))
      case _ =>
        None
    }
  }
}
