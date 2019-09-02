package scala.meta.internal.builds

import java.security.MessageDigest
import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.JFiles

object MavenDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    Digest.digestFile(workspace.resolve("pom.xml"), digest)
    JFiles.walk(workspace).forall { file =>
      if (file.filename == "pom.xml") {
        Digest.digestFile(file, digest)
      } else {
        true
      }
    }
  }
}
