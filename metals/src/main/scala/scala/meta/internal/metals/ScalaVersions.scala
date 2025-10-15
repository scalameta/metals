package scala.meta.internal.metals

import java.io.UncheckedIOException

import scala.collection.concurrent.TrieMap

import scala.meta.Dialect
import scala.meta.dialects._
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

class ScalaVersions(
    deprecatedScalaVersions: Seq[String],
    supportedScalaVersions: Seq[String],
    supportedScalaBinaryVersions: Seq[String],
    scala212: String,
    scala213: String,
    scala3: String,
) {

  private val jarScalaVersionIndex = TrieMap[String, String]()

  def isScala3Milestone(version: String): Boolean =
    version.startsWith("3.0.0-M") || version.startsWith("3.0.0-RC")

  /**
   * Non-Lightbend compilers often use a suffix, such as `-bin-typelevel-4`
   */
  def dropVendorSuffix(version: String): String =
    version.replaceAll("-bin-.*", "")

  private val _isDeprecatedScalaVersion: Set[String] =
    deprecatedScalaVersions.toSet
  private val _isSupportedScalaVersion: Set[String] =
    supportedScalaVersions.toSet

  def isSupportedAtReleaseMomentScalaVersion(version: String): Boolean = {
    val versionWithoutVendorSuffix = dropVendorSuffix(version)
    SemVer.isLaterVersion("3.3.3", versionWithoutVendorSuffix) ||
    _isSupportedScalaVersion(versionWithoutVendorSuffix)
  }

  def isDeprecatedScalaVersion(version: String): Boolean =
    _isDeprecatedScalaVersion(dropVendorSuffix(version))

  def isSupportedScalaBinaryVersion(scalaVersion: String): Boolean =
    supportedScalaBinaryVersions.exists { binaryVersion =>
      scalaVersion.startsWith(binaryVersion)
    }

  def isScala3Version(scalaVersion: String): Boolean =
    scalaVersion.startsWith("3.")

  def supportedScala3Versions: Set[String] =
    supportedScalaVersions.filter(isScala3Version(_)).toSet

  val isLatestScalaVersion: Set[String] =
    Set(scala212, scala213, scala3)

  def latestStableVersionFor(scalaVersion: String): Option[String] = {
    val binaryVersion = scalaBinaryVersionFromFullVersion(scalaVersion)
    isLatestScalaVersion
      .find(latest =>
        binaryVersion == scalaBinaryVersionFromFullVersion(latest)
      )
  }

  /**
   * Latest supported version that has the same binary version
   */
  def latestSupportedVersionFor(scalaVersion: String): Option[String] = {
    val binaryVersion = scalaBinaryVersionFromFullVersion(scalaVersion)
    supportedScalaVersions
      .filter(scalaBinaryVersionFromFullVersion(_) == binaryVersion)
      .toList
      .sortWith(SemVer.isLaterVersion(_, _))
      .lastOption
  }

  def recommendedVersion(scalaVersion: String): String = {
    val parsedVersion = SemVer.Version.fromString(scalaVersion)
    val latestVersion = if (parsedVersion.releaseCandidate.isDefined) {
      // if the project is currently using an RC recommend latest suported version, possibly a RC
      latestSupportedVersionFor(scalaVersion)
    } else {
      // otherwise recommend the latest stable version
      latestStableVersionFor(scalaVersion)
    }
    val defaultVersion = if (isScala3Version(scalaVersion)) {
      scala3
    } else {
      scala212
    }
    latestVersion.getOrElse(defaultVersion)
  }

  def isFutureVersion(scalaVersion: String): Boolean = {
    def isFuture = latestSupportedVersionFor(scalaVersion)
      .map(latest =>
        latest != scalaVersion && SemVer
          .isLaterVersion(latest, scalaVersion)
      )
      .getOrElse {
        val versions =
          if (isScala3Version(scalaVersion))
            isLatestScalaVersion.filter(isScala3Version)
          else
            isLatestScalaVersion.filter(!isScala3Version(_))
        versions.forall(ver => SemVer.isLaterVersion(ver, scalaVersion))
      }
    !supportedScala3Versions(scalaVersion) && isFuture
  }

  def scalaBinaryVersionFromFullVersion(scalaVersion: String): String = {
    if (scalaVersion.startsWith("3"))
      "3"
    else
      scalaVersion.split('.').take(2).mkString(".")
  }

  /**
   * Select scalameta dialect for a Scala version
   *
   * @param scalaVersion
   * @param includeSource3 if to use dialect with Source3, which will parse Scala 2 code
   * that compiles with the -Xsource:3 flag. In some cases where we don't use diagnostics
   * it makes sense to always use Source3 dialects.
   */
  def dialectForScalaVersion(
      scalaVersion: String,
      includeSource3: Boolean,
  ): Dialect = {
    val scalaBinaryVersion = scalaBinaryVersionFromFullVersion(scalaVersion)
    scalaBinaryVersion match {
      case "2.11" => Scala211
      case "2.12" if includeSource3 => Scala212Source3
      case "2.12" => Scala212
      case "2.13" if includeSource3 => Scala213Source3
      case "2.13" => Scala213
      case version if version.startsWith("3") => Scala3
      case _ => Scala213
    }
  }

  def fmtDialectForScalaVersion(
      scalaVersion: String,
      includeSource3: Boolean,
  ): ScalafmtDialect = {
    scalaBinaryVersionFromFullVersion(scalaVersion) match {
      case "3" => ScalafmtDialect.Scala3
      case "2.13" if includeSource3 => ScalafmtDialect.Scala213Source3
      case "2.13" => ScalafmtDialect.Scala213
      case "2.12" if includeSource3 => ScalafmtDialect.Scala212Source3
      case "2.12" => ScalafmtDialect.Scala212
      case "2.11" => ScalafmtDialect.Scala211
    }
  }

  private val scalaVersionRegex =
    raw"_(\d)(\.\d{1,2})?(\.\d(-(RC|M)\d)?)?".r
  private val scalaLibraryRegex =
    raw"scala-library-(\d)(\.\d{1,2})(\.\d(-(RC|M)\d)?)".r

  /**
   * Extract scala binary version from dependency jar name.
   * The version that starts from `_` prefix takes more precedence.
   * Example:
   *   `scala-library-2.13.5` -> 2.13
   *   `some-library_2.13-4.5.0` -> 2.13
   *   `some-library_2.13-2.11` -> 2.13
   */
  def scalaBinaryVersionFromJarName(filename: String): Option[String] = {
    val dropEnding = filename.stripSuffix(".jar")

    List(scalaLibraryRegex, scalaVersionRegex)
      .flatMap(_.findAllMatchIn(dropEnding).toList)
      .flatMap { m =>
        val major = m.group(1)
        val minor = Option(m.group(2)).getOrElse("")
        val ending = Option(m.group(3)).getOrElse("")
        val version = s"$major$minor$ending"

        if (isSupportedScalaBinaryVersion(version))
          Some(version)
        else None
      }
      .headOption
      .map(scalaBinaryVersionFromFullVersion)
  }

  def dialectForDependencyJar(
      jar: AbsolutePath,
      buildTargets: BuildTargets,
  ): Dialect = {
    lazy val buildTargetAndScalaVersion =
      buildTargets
        .inverseDependencySource(jar)
        .flatMap(id => buildTargets.scalaTarget(id))
        .map(target => (target.scalaBinaryVersion, target.id))
        .toList
        .sortBy(_._1)
        .headOption

    def fromTastyExistance = {
      val fromTasty = buildTargetAndScalaVersion
        .flatMap { case (_, id) => buildTargets.findJarFor(id, jar) }
        .flatMap(
          FileIO.withJarFileSystem(_, create = false) { root =>
            try {
              root.listRecursive
                .find(f => f.isFile && f.filename.endsWith(".tasty"))
                .map(_ => "3")
                .orElse(Some("2.13"))
            } catch {
              case _: UncheckedIOException => None
            }
          }
        )
      fromTasty.foreach(jarScalaVersionIndex.put(jar.toURI.toString(), _))
      fromTasty
    }

    val scalaVersion =
      scalaBinaryVersionFromJarName(jar.toNIO.getFileName().toString())
        .orElse(jarScalaVersionIndex.get(jar.toURI.toString()))
        .orElse(fromTastyExistance)
        .orElse(buildTargetAndScalaVersion.map(_._1))
        .getOrElse("2.13")
    dialectForScalaVersion(scalaVersion, includeSource3 = true)
  }

}

object ScalaVersions
    extends ScalaVersions(
      BuildInfo.deprecatedScalaVersions,
      BuildInfo.supportedScalaVersions,
      BuildInfo.supportedScalaBinaryVersions,
      BuildInfo.scala212,
      BuildInfo.scala213,
      BuildInfo.scala3,
    )
