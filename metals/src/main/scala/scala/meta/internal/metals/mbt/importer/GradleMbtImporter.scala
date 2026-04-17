package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.GradleDigest
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.io.AbsolutePath

final class GradleMbtImporter(
    override val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  override def name: String = "gradle"

  override def extract(workspace: AbsolutePath): Future[Unit] = Future {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)
    Files.writeString(out.toNIO, MbtBuild.toJson(MbtBuild.empty))
  }

  override def isBuildRelated(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    GradleBuildTool.isGradleRelatedPath(workspace, path)

  override def digest(workspace: AbsolutePath): Option[String] =
    GradleDigest.current(projectRoot)
}
