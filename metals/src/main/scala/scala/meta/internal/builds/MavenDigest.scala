package scala.meta.internal.builds

import java.security.MessageDigest
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

object MavenDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    Digest.digestFile(workspace.resolve("pom.xml"), digest)
    workspace.listRecursive.forall {
      case file if file.filename == "pom.xml" =>
        Digest.digestFile(file, digest)
      case _ =>
        true
    }
  }
}
