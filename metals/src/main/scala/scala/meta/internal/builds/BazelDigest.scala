package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object BazelDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    workspace.listRecursive.forall {
      case file if file.filename == "BUILD" || file.filename == "WORKSPACE" =>
        Digest.digestFile(file, digest)
      case _ =>
        true
    }
  }
}
