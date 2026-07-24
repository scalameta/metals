package scala.meta.internal.metals.mbt.importer

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.semver.SemVer

object BazelScalaVersions {

  // Highest SemVer-parseable version; `scala_version` is a free-form STRING, so
  // unparseable values are logged and skipped.
  def maxVersion(versions: Iterable[String]): Option[String] =
    versions.toSeq.distinct
      .flatMap { version =>
        Try(SemVer.Version.fromString(version)) match {
          case Success(parsed) => Some(version -> parsed)
          case Failure(_) =>
            scribe.warn(
              s"bazel-mbt: could not parse Scala version '$version'; ignoring it"
            )
            None
        }
      }
      .maxByOption { case (_, parsed) => parsed }
      .map { case (version, _) => version }

}
