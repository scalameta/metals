package tests

import scala.meta.internal.builds.Digest
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.Messages._
import munit.Location
import scala.meta.internal.metals.ClientExperimentalCapabilities

abstract class BaseImportSuite(suiteName: String)
    extends BaseLspSuite(suiteName) {

  override def experimentalCapabilities
      : Option[ClientExperimentalCapabilities] =
    Some(ClientExperimentalCapabilities(slowTaskProvider = true))

  def buildTool: BuildTool

  def importBuildMessage: String =
    ImportBuild.params(buildTool.toString()).getMessage

  def importBuildChangesMessage: String =
    ImportBuildChanges.params(buildTool.toString()).getMessage

  def progressMessage: String =
    bloopInstallProgress(buildTool.executableName).message

  def currentDigest(workspace: AbsolutePath): Option[String]

  def currentChecksum(): String =
    currentDigest(workspace).getOrElse {
      fail("no checksum for workspace")
    }
  def assertNoStatus()(implicit loc: Location): Unit =
    server.server.tables.digests.getStatus(currentChecksum()) match {
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
