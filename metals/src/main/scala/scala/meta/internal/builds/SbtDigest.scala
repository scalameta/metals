package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.builds.Digest.digestScala
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.ListFiles

object SbtDigest extends Digestable {

  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val project = workspace.resolve("project")
    digestSbtFiles(workspace, digest) &&
    Digest.digestFileBytes(project.resolve("build.properties"), digest) &&
    Digest.digestDirectory(project, digest) &&
    Digest.digestDirectory(project.resolve("project"), digest)
  }

  def digestSbtFiles(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (!path.isDirectory) {
      true
    } else {
      ListFiles.forall(path)(file => digestSbtFile(digest)(file))
    }
  }

  private def digestSbtFile(digest: MessageDigest)(path: AbsolutePath) = {
    if (path.isFile && path.extension == "sbt") {
      digestScala(path, digest)
    } else {
      true
    }
  }

}
