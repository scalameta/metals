package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object BazelDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest,
  ): Boolean = {
    workspace.listRecursive.forall {
      // TODO: *.bzl also should be detected
      // https://github.com/scalameta/metals/issues/5144
      case file
          if file.filename == "BUILD" ||
            file.filename == "WORKSPACE" ||
            file.filename == "BUILD.bazel" =>
        Digest.digestFile(file, digest)
      case _ =>
        true
    }
  }
}
