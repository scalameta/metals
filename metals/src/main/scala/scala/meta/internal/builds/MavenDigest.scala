package scala.meta.internal.builds
import java.security.MessageDigest
import scala.meta.io.AbsolutePath

object MavenDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    Digest.digestFile(workspace.resolve("pom.xml"), digest)
    walkDirectory(workspace.toNIO) {
      _.allMatch { file =>
        if (file.getFileName.toString == "pom.xml") {
          Digest.digestFile(AbsolutePath(file), digest)
        } else {
          true
        }
      }
    }
  }
}
