package tests

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.Digest
import scala.meta.internal.metals.Messages._
import scala.meta.io.AbsolutePath

import munit.Location

abstract class BaseImportSuite(
    suiteName: String,
    initializer: BuildServerInitializer = BloopImportInitializer,
) extends BaseLspSuite(suiteName, initializer) {

  def buildTool: BuildTool

  def importBuildMessage: String =
    ImportBuild.params(buildTool.toString()).getMessage

  def importBuildChangesMessage: String =
    ImportBuildChanges.params(buildTool.toString()).getMessage

  def progressMessage: String =
    s"${buildTool.executableName} bloopInstall"

  def currentDigest(workspace: AbsolutePath): Option[String]

  def currentChecksum(): String =
    currentDigest(workspace).getOrElse {
      fail("no checksum for workspace")
    }
  def assertNoStatus()(implicit loc: Location): Unit =
    server.server.tables.digests
      .getStatus(currentChecksum()) match {
      case Some(value) =>
        fail(s"expected no status. obtained $value")
      case None =>
        () // OK
    }
  def assertStatus(
      fn: Digest.Status => Boolean
  )(implicit loc: Location): Unit = {
    val checksum = currentChecksum()
    server.server.tables.digests.getStatus(checksum) match {
      case Some(status) =>
        assert(fn(status))
      case None =>
        fail(s"missing persisted checksum $checksum")
    }
  }
}
