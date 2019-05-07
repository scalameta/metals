package tests

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.builds.Digest
import scala.meta.io.AbsolutePath

abstract class BaseImportSuite(suiteName: String)
    extends BaseSlowSuite(suiteName) {

  def currentDigest(workspace: AbsolutePath): Option[String]

  def currentChecksum(): String =
    currentDigest(workspace).getOrElse {
      fail("no checksum for workspace")
    }
  def assertNoStatus(): Unit =
    server.server.tables.digests.getStatus(currentChecksum()) match {
      case Some(value) =>
        fail(s"expected no status. obtained $value", stackBump = 1)
      case None =>
        () // OK
    }
  def assertStatus(fn: Digest.Status => Boolean): Unit = {
    val checksum = currentChecksum()
    server.server.tables.digests.getStatus(checksum) match {
      case Some(status) =>
        assert(fn(status))
      case None =>
        fail(s"missing persisted checksum $checksum", stackBump = 1)
    }
  }

  override def testAsync(
      name: String,
      maxDuration: Duration
  )(run: => Future[Unit]): Unit = {
    if (isWindows) {
      // Skip SbtSlowSuite on Windows because they're flaky due to likely the small
      // available memory on Appveyor CI machines.
      ignore(name)(())
    } else {
      super.testAsync(name, maxDuration)(run)
    }
  }

}
