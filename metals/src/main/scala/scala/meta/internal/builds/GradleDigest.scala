package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

object GradleDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    val buildSrc = workspace.resolve("buildSrc")
    val buildSrcDigest = if (buildSrc.isDirectory) {
      digestBuildSrc(buildSrc, digest)
    } else {
      true
    }
    buildSrcDigest &&
    Digest.digestDirectory(workspace, digest) &&
    digestSubProjects(workspace, digest)
  }

  def digestBuildSrc(path: AbsolutePath, digest: MessageDigest): Boolean = {
    path.listRecursive.forall { file => Digest.digestFile(file, digest) }
  }

  def digestSubProjects(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    val directories = workspace.list.filter(_.isDirectory).toList

    val (subprojects, dirs) = directories.partition { file =>
      file.list.exists { path =>
        val stringPath = path.toString
        stringPath.endsWith(".gradle") || stringPath.endsWith("gradle.kts")
      }
    }
    /*
       If a dir contains a gradle file we need to treat is as a workspace
     */
    val isSuccessful = subprojects.forall { file =>
      digestWorkspace(file, digest)
    }

    /*
       If it's a dir we need to keep searching since gradle can have non trivial workspace layouts
     */
    isSuccessful && dirs.forall { file => digestSubProjects(file, digest) }
  }
}
