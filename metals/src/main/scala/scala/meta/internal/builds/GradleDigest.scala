package scala.meta.internal.builds

import scala.meta.io.AbsolutePath
import scala.meta.internal.mtags.WalkFiles
import scala.meta.internal.jdk.CollectionConverters._
import java.security.MessageDigest
import java.nio.file.Files
import java.util.stream.Collectors
import java.nio.file.Path

object GradleDigest extends Digestable {
  override protected def digestWorkspace(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val buildSrc = workspace.resolve("buildSrc")
    val buildSrcDigest = if (buildSrc.isDirectory) {
      digestBuildSrc(buildSrc, digest)
    } else {
      true
    }
    buildSrcDigest && Digest.digestDirectory(workspace, digest) && digestSubProjects(
      workspace,
      digest
    )
  }

  def digestBuildSrc(path: AbsolutePath, digest: MessageDigest): Boolean = {
    WalkFiles.foreach(path) { file =>
      Digest.digestFile(file, digest)
    }
    true
  }

  def digestSubProjects(
      workspace: AbsolutePath,
      digest: MessageDigest
  ): Boolean = {
    val (subprojects, dirs) = Files
      .list(workspace.toNIO)
      .filter(Files.isDirectory(_))
      .collect(Collectors.toList[Path])
      .asScala
      .partition { file =>
        Files
          .list(file)
          .anyMatch { path =>
            val stringPath = path.toString
            stringPath.endsWith(".gradle") || stringPath.endsWith("gradle.kts")
          }
      }
    /*
       If a dir contains a gradle file we need to treat is as a workspace
     */
    val isSuccessful = subprojects.forall { file =>
      digestWorkspace(
        AbsolutePath(file),
        digest
      )
    }

    /*
       If it's a dir we need to keep searching since gradle can have non trivial workspace layouts
     */
    isSuccessful && dirs.forall { file =>
      digestSubProjects(AbsolutePath(file), digest)
    }
  }
}
