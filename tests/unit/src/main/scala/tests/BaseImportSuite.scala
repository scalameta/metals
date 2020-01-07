package tests

import scala.meta.internal.builds.Digest
import scala.meta.io.AbsolutePath
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.SlowTaskConfig
import scala.meta.internal.metals.MetalsServerConfig

abstract class BaseImportSuite(suiteName: String)
    extends BaseLspSuite(suiteName) {

  // enables support for bloop install (a slow task)
  override val serverConfig: MetalsServerConfig = {
    super.serverConfig.copy(slowTask = SlowTaskConfig.on)
  }

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
  def assertNoStatus(): Unit =
    server.server.tables.digests.getStatus(currentChecksum()) match {
      case Some(value) =>
        fail(s"expected no status. obtained $value")
      case None =>
        () // OK
    }
  def assertStatus(fn: Digest.Status => Boolean): Unit = {
    val checksum = currentChecksum()
    server.server.tables.digests.getStatus(checksum) match {
      case Some(status) =>
        assert(fn(status))
      case None =>
        fail(s"missing persisted checksum $checksum")
    }
  }
}
