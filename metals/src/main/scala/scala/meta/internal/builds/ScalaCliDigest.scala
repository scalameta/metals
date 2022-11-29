package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.io.AbsolutePath

object ScalaCliDigest extends Digestable {
  protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = Digest.digestDirectory(workspace, digest)
}
