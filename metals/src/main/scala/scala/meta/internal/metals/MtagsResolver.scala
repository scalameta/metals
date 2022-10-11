package scala.meta.internal.metals

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

import coursierapi.error.SimpleResolutionError
import org.jsoup.Jsoup

trait MtagsResolver {
  def resolve(scalaVersion: String): Option[MtagsBinaries]
  final def isSupportedScalaVersion(version: String): Boolean =
    resolve(version).isDefined
}

object MtagsResolver {

  def default(): MtagsResolver = new Default

  class Default extends MtagsResolver {

    private val states =
      new ConcurrentHashMap[String, State]()

    def resolve(scalaVersion: String): Option[MtagsBinaries] = {
      resolve(scalaVersion, original = None)
    }

    private def resolve(
        scalaVersion: String,
        original: Option[String],
    ): Option[MtagsBinaries] = {
      def logError(e: Throwable): Unit = {
        val msg = s"Failed to fetch mtags for ${scalaVersion}"
        e match {
          case _: SimpleResolutionError =>
            // no need to log traces for coursier error
            // all explanation is in message
            scribe.error(msg + "\n" + e.getMessage())
          case _ =>
            scribe.error(msg, e)
        }
      }

      def fetch(tries: Int = 0): State = {
        try {
          val jars = Embedded.downloadMtags(scalaVersion)
          State.Success(MtagsBinaries.Artifacts(scalaVersion, jars))
        } catch {
          case NonFatal(e) =>
            logError(e)
            State.Failure(System.currentTimeMillis(), tries)
        }
      }
      def shouldResolveAgain(failure: State.Failure): Boolean = {
        failure.tries < State.maxTriesInARow ||
        (System
          .currentTimeMillis() - failure.lastTryMillis) > 5.minutes.toMillis
      }

      // The metals_2.12 artifact depends on mtags_2.12.x where "x" matches
      // `mtags.BuildInfo.scalaCompilerVersion`. In the case when
      // `info.getScalaVersion == mtags.BuildInfo.scalaCompilerVersion` then we
      // skip fetching the mtags module from Maven.
      if (MtagsBinaries.isBuildIn(scalaVersion)) {
        Some(MtagsBinaries.BuildIn)
      } else {
        val computed = states.compute(
          original.getOrElse(scalaVersion),
          (_, value) => {
            value match {
              case null => fetch()
              case succ: State.Success => succ
              case failure: State.Failure =>
                if (shouldResolveAgain(failure))
                  fetch(failure.tries + 1)
                else {
                  scribe.info(s"No mtags for ${scalaVersion}.")
                  failure
                }
            }
          },
        )
        computed match {
          case State.Success(v) => Some(v)
          // Try to download latest supported snapshot
          case _: State.Failure
              if original.isEmpty && scalaVersion.contains("NIGHTLY") =>
            findLatestSnapshot(scalaVersion) match {
              case None => None
              case Some(latestSnapshot) =>
                scribe.warn(s"Using latest stable version $latestSnapshot")
                resolve(
                  latestSnapshot,
                  Some(scalaVersion),
                )
            }
          case _ =>
            None
        }
      }
    }

    /**
     * Nightlies version are able to work with artifacts compiled within the
     * same RC version.
     *
     * For example 3.2.2-RC1-bin-20221009-2052fc2-NIGHTLY presentation compiler
     * will work with classfiles compiled with 3.2.2-RC1-bin-20220910-ac6cd1c-NIGHTLY
     *
     * @param exactVersion version we failed to find and looking for an alternative for
     * @return latest supported nightly version by thise version of metals
     */
    private def findLatestSnapshot(exactVersion: String): Option[String] = try {

      val metalsVersion = BuildInfo.metalsVersion

      // strip timestamp to get only 3.2.2-RC1
      val rcVersion = SemVer.Version
        .fromString(exactVersion)
        .copy(nightlyDate = None)
        .toString()

      val url =
        s"https://oss.sonatype.org/content/repositories/snapshots/org/scalameta/"

      val allScalametaArtifacts = Jsoup.connect(url).get

      // find all the nightlies for current RC
      val lastNightlies = allScalametaArtifacts
        .select("a")
        .asScala
        .filter { a =>
          val name = a.text()
          name.contains("NIGHTLY") && name.contains(rcVersion)
        }

      // find last supported Scala version for this metals version
      lastNightlies.reverseIterator
        .find { nightlyLink =>
          val link = nightlyLink.attr("href")

          val mtagsPage = Jsoup.connect(link).get

          mtagsPage
            .select("a")
            .asScala
            .find(_.text() == metalsVersion + "/")
            .isDefined
        }
        .map(_.text().stripPrefix("mtags_").stripSuffix("/"))

    } catch {
      case NonFatal(t) =>
        scribe.error("Could not check latest nightlies", t)
        None
    }

    sealed trait State
    object State {
      val maxTriesInARow: Int = 2
      case class Success(v: MtagsBinaries.Artifacts) extends State
      case class Failure(lastTryMillis: Long, tries: Int) extends State
    }
  }

}
