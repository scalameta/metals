package scala.meta.internal.builds

import scala.meta.io.AbsolutePath
import java.security.MessageDigest
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.pantsbuild.PantsConfiguration

class PantsDigest(userConfig: () => UserConfiguration) extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    userConfig().pantsTargets match {
      case None =>
        scribe.info(
          "skipping build import for Pants workspace since the setting 'pants-targets' is not defined. " +
            "To fix this problem, update the 'pants-targets' setting to list what build targets should be imported in this workspace."
        )
        false
      case Some(pantsTargets) =>
        digestBuildFiles(workspace, digest, pantsTargets)
    }
  }

  private def digestBuildFiles(
      workspace: AbsolutePath,
      digest: MessageDigest,
      pantsTargets: List[String]
  ): Boolean = {
    var isOk = true
    for {
      root <- PantsConfiguration.sourceRoots(workspace, pantsTargets)
      buildFile <- root.listRecursive.filter(_.toNIO.endsWith("BUILD"))
    } {
      isOk &= Digest.digestFile(buildFile, digest)
    }
    isOk
  }

}
