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
    if (checkCoursier)
      default.resolve(scalaVersion).orElse(localCheck(scalaVersion))
    else localCheck(scalaVersion)
  }

  override def isSupportedInOlderVersion(version: String): Boolean =
    default.isSupportedInOlderVersion(version)
}
