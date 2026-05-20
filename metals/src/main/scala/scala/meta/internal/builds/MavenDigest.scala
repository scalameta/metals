package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object MavenDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    val digestedPoms = workspace.listRecursive.forall {
      case file if file.filename == "pom.xml" =>
        Digest.digestFile(file, digest)
      case _ =>
        true
    }

    val mvnDir = workspace.resolve(".mvn")
    val digestedMvn =
      List("toolchains.xml", "extensions.xml")
        .forall { name =>
          Digest.digestFile(mvnDir.resolve(name), digest)
        } &&
        List("maven.config", "jvm.config")
          .forall { name =>
            Digest.digestFileBytes(mvnDir.resolve(name), digest)
          }

    digestedPoms && digestedMvn
  }
}
