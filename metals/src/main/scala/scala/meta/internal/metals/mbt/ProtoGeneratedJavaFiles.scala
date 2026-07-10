package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Materializes the Java source that Metals synthesizes from a `.proto` file
 * (via [[MbtProtobufWorkspaceSymbolProvider]]) into a read-only file on disk,
 * so that goto-definition on a proto-generated class can open it.
 *
 * The outline is derived purely from the `.proto` file, so this needs no build
 * output and no configuration and works for every build tool. Files live under
 * `.metals/readonly/` so clients treat them as read-only dependency sources.
 */
object ProtoGeneratedJavaFiles {

  /** Subdirectory of `.metals/readonly/dependencies` for generated outlines. */
  private val rootDirName = "proto-generated"

  /**
   * Writes `content` for `javaPackagePath/className.java` generated from
   * `protoPath` to a stable on-disk location and returns it.
   *
   * @param javaPackagePath slash-separated package with a trailing slash
   *                        (e.g. `com/example/jproto/`), or empty for the
   *                        default package
   */
  def materialize(
      workspace: AbsolutePath,
      protoPath: AbsolutePath,
      javaPackagePath: String,
      className: String,
      content: String,
  ): Option[AbsolutePath] =
    try {
      protoPath.toRelativeInside(workspace).map { protoRelative =>
        val javaFile = workspace
          .resolve(Directories.dependencies)
          .resolve(rootDirName)
          .resolveZipPath(protoRelative.toNIO)
          .resolveZipPath(Paths.get(javaPackagePath))
          .resolve(s"$className.java")
        writeIfChanged(javaFile, content)
        javaFile
      }
    } catch {
      case NonFatal(e) =>
        scribe.debug(
          s"proto-java: failed to materialize generated outline for $className",
          e,
        )
        None
    }

  private def writeIfChanged(file: AbsolutePath, content: String): Unit = {
    val current =
      if (file.exists) Some(FileIO.slurp(file, StandardCharsets.UTF_8))
      else None
    if (!current.contains(content)) {
      Files.createDirectories(file.toNIO.getParent)
      Files.write(file.toNIO, content.getBytes(StandardCharsets.UTF_8))
    }
  }
}
