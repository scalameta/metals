package scala.meta.metals

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

import org.jsoup.Jsoup

abstract class SupportedScalaVersions {

  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global

  type SupportedVersions = String
  type SupportedAtRelease = String

  def supportedVersionsString(
      version: String,
      timeout: FiniteDuration,
  ): Either[SupportedAtRelease, SupportedVersions] = {
    findAllSupported(version, timeout) match {
      case None =>
        Left(
          formatVersions(
            BuildInfo.supportedScalaVersions
          )
        )
      case Some(value) => Right(value)
    }

  }

  private def findAllSupported(
      metalsVersion: String,
      timeout: FiniteDuration,
  ) = {
    val url =
      if (metalsVersion.contains("SNAPSHOT"))
        "https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/org/scalameta/"
      else "https://repo1.maven.org/maven2/org/scalameta/"
    supportedInMetals(url, metalsVersion, timeout)
  }

  private def supportedInMetals(
      url: String,
      metalsVersion: String,
      timeout: FiniteDuration,
  ): Option[String] =
    try {
      println(s"Checking available versions on $url\n")
      val allScalametaArtifacts = Jsoup.connect(url).get

      val allMdocs = allScalametaArtifacts
        .select("a")
        .asScala
        .filter { a =>
          val name = a.text()
          name.contains("mtags_")
        }

      // find all supported Scala versions for this metals version
      val allSupportedScalaFut = allMdocs.iterator
        .map { mdocLink =>
          // let's not check nightly Scala versions
          if (mdocLink.text().contains("NIGHTLY"))
            Future.successful(None)
          else {
            val link = mdocLink.attr("href")
            val mtagsLink = if (link.startsWith("http")) link else url + link
            Future {
              Try(Jsoup.connect(mtagsLink + metalsVersion).get)
            }.map {
              case Success(_) =>
                Some(mdocLink.text().stripPrefix("mtags_").stripSuffix("/"))
              case Failure(_) => None
            }

          }
        }

      val allSupportedScala =
        Await.result(Future.sequence(allSupportedScalaFut), timeout).flatten
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
        scribe.error(
          "Could not check supported Scala versions, returning list available at release",
          t,
        )
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
