package tests

import scala.meta.internal.metals.MtagsBinaries
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.ScalaVersions

/**
 * The default MtagsResolver always checks if mtags-artifacts for particular scala version exists.
 * However this strict logic doesn't work for tests.
 * For `unit` we don't publish mtags at all but there are some tests that trigger docker check.
 * So for these cases, do fallback on previous mechanic by checking declared supported versions.
 */
class TestMtagsResolver extends MtagsResolver {

  val default: MtagsResolver = MtagsResolver.default()
  override def resolve(scalaVersion: String): Option[MtagsBinaries] = {
    default.resolve(scalaVersion).orElse(fakeBinaries(scalaVersion))
  }

  private def fakeBinaries(scalaVersion: String): Option[MtagsBinaries] = {
    if (ScalaVersions.isSupportedAtReleaseMomentScalaVersion(scalaVersion))
      Some(MtagsBinaries.BuildIn)
    else
      None
  }
}
