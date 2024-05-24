package scala.meta.metals

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

import org.jsoup.Jsoup

abstract class SupportedScalaVersions {

  def supportedVersionsString(version: String): String = {
    findAllSupported(version).getOrElse(
      formatVersions(BuildInfo.supportedScalaVersions)
    )
  }

  private def findAllSupported(metalsVersion: String) = {
    if (metalsVersion.contains("SNAPSHOT")) {
      supportedInMetals(
        "https://oss.sonatype.org/content/repositories/snapshots/org/scalameta/",
        metalsVersion,
      )
    } else {
      supportedInMetals(
        "https://repo1.maven.org/maven2/org/scalameta/",
        metalsVersion,
      )
    }
  }

  private def supportedInMetals(
      url: String,
      metalsVersion: String,
  ): Option[String] =
    try {

      val allScalametaArtifacts = Jsoup.connect(url).get

      val allMdocs = allScalametaArtifacts
        .select("a")
        .asScala
        .filter { a =>
          val name = a.text()
          name.contains("mtags_")
        }

      // find all supported Scala versions for this metals version
      val allSupportedScala = allMdocs.iterator
        .filter { mdocLink =>
          // let's not check nightly Scala versions
          if (mdocLink.text().contains("NIGHTLY")) false
          else {
            val link = mdocLink.attr("href")
            val mtagsLink = if (link.startsWith("http")) link else url + link
            Try {
              Jsoup.connect(mtagsLink + metalsVersion).get
            } match {
              case Success(_) => true
              case Failure(_) => false
            }

          }
        }
        .map(_.text().stripPrefix("mtags_").stripSuffix("/"))

      val template = formatVersions(allSupportedScala.toList)

      Some(
        s"""|$template
            |Scala 3 versions from 3.3.4 are automatically supported by Metals.
            |
            |Any older Scala versions will no longer get bugfixes, but should still
            |work properly with newest Metals. 
            |""".stripMargin
      )
    } catch {
      case NonFatal(t) =>
        scribe.error("Could not check supported Scala versions", t)
        None
    }

  protected def formatSingle(major: String, versions: Seq[String]): String

  private def formatVersions(versions: Seq[String]) = {
    versions.toList
      .map(SemVer.Version.fromString)
      .groupBy { version =>
        if (version.major == 2) version.major.toString + "." + version.minor
        else version.major.toString()
      }
      .toList
      .sortBy(_._1)
      .map { case (major, vers) =>
        formatSingle(major, vers.sortBy(_.patch).map(_.toString()))
      }
      .mkString("")
  }
}
