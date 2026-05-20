package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.io.AbsolutePath

object DederDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    val dederFile = workspace.resolve("deder.pkl")
    if (dederFile.isFile) Digest.digestFile(dederFile, digest)
    else false
  }
}
