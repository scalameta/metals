package scala.meta.internal.builds

import scala.meta.io.AbsolutePath
import java.security.MessageDigest

object SbtDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val project = workspace.resolve("project")
    Digest.digestDirectory(workspace, digest) &&
    Digest.digestFileBytes(project.resolve("build.properties"), digest) &&
    Digest.digestDirectory(project, digest) &&
    Digest.digestDirectory(project.resolve("project"), digest)
  }
}
