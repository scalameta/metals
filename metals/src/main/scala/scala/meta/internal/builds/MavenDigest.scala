package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

object MavenDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    workspace.listRecursive.forall {
      case file if file.filename == "pom.xml" =>
        Digest.digestFile(file, digest)
      case _ =>
        true
    }
  }
}
