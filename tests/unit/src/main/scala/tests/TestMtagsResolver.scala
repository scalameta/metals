package tests

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ScalaVersions

/**
 * The default MtagsResolver always checks if mtags-artifacts for particular scala version exists.
 * However this strict logic doesn't work for tests.
 * For `unit` we don't publish mtags at all but there are some tests that trigger docker check.
 * So for these cases, do fallback on previous mechanic by checking declared supported versions.
 */
class TestMtagsResolver(checkCoursier: Boolean) extends MtagsResolver {

  val default: MtagsResolver = MtagsResolver.default()

  private def localCheck(scalaVersion: String) =
    if (ScalaVersions.isSupportedAtReleaseMomentScalaVersion(scalaVersion))
      Some(MtagsBinaries.BuildIn)
    else None

  override def resolve(
      scalaVersion: String
  )(implicit ec: ExecutionContext): Option[MtagsBinaries] = {
    // Only use Coursier for versions that are locally supported. Without this guard,
    // after `publishLocal`, `default.resolve("2.12.4")` would succeed (maps to
    // mtags_2.12.21:1.5.1-SNAPSHOT in ivy2Local), making isSupportedScalaVersion("2.12.4")
    // return true and suppressing the UnsupportedScalaVersion warning in tests.
    if (checkCoursier && localCheck(scalaVersion).isDefined)
      default.resolve(scalaVersion).orElse(localCheck(scalaVersion))
    else localCheck(scalaVersion)
  }

  override def isSupportedInOlderVersion(version: String): Boolean =
    default.isSupportedInOlderVersion(version)
}
