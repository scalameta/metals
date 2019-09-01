package scala.meta.internal.builds

import java.security.MessageDigest
import scala.meta.internal.builds.Digest.digestScala
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import java.nio.file.Path

object SbtDigest extends Digestable {
  val sbtExtension = "sbt"

  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val project = workspace.resolve("project")
    digestSbtFiles(workspace, digest) &&
    Digest.digestFileBytes(project.resolve("build.properties"), digest) &&
    Digest.digestDirectory(project, digest) &&
    Digest.digestDirectory(project.resolve("project"), digest)
  }

  def digestSbtFiles(
      path: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    if (!path.isDirectory) {
      true
    } else {
      listFiles(path.toNIO) { _.allMatch(digestSbtFile(digest)) }
    }
  }

  private def digestSbtFile(digest: MessageDigest)(filePath: Path) = {
    val path = AbsolutePath(filePath)
    def ext = PathIO.extension(path.toNIO)
    if (path.isFile && ext == sbtExtension) {
      digestScala(path, digest)
    } else {
      true
    }
  }

}
