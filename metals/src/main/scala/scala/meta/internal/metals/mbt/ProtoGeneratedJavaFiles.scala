package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.util.control.NonFatal

import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
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
   * Writes `content` for `className.java` generated from `protoPath` to a
   * stable on-disk location and returns it.
   *
   * The Java package is intentionally not reflected in the directory layout:
   * it is already declared inside the file, no consumer derives it from the
   * path, and class names are unique within a single `.proto`.
   */
  def materialize(
      workspace: AbsolutePath,
      protoPath: AbsolutePath,
      className: String,
      content: String,
  ): Option[AbsolutePath] =
    try {
      protoPath.toRelativeInside(workspace).map { protoRelative =>
        val javaFile = workspace
          .resolve(Directories.dependencies)
          .resolve(rootDirName)
          .resolveZipPath(protoRelative.toNIO)
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

  /**
   * Inverse of [[materialize]]: recovers the `.proto` file that the given
   * materialized Java file was generated from, or `None` when the path is not
   * a materialized proto outline. The proto path is the leading segments of
   * the path relative to the `proto-generated` root, up to and including the
   * first segment with a `.proto` extension.
   */
  def protoPathFor(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Option[AbsolutePath] = {
    val root = workspace
      .resolve(Directories.dependencies)
      .resolve(rootDirName)
    for {
      relative <- path.toRelativeInside(root)
      segments = relative.toNIO.iterator().asScala.map(_.toString).toList
      protoIndex = segments.indexWhere(_.endsWith(".proto"))
      if protoIndex >= 0
    } yield segments
      .take(protoIndex + 1)
      .foldLeft(workspace)(_.resolve(_))
  }

  /**
   * Recreates the materialized outline for `outlineFile` when it has been
   * deleted (for example after a `.metals` clean, a `git clean`, or an editor
   * reload), so navigation, hover, and completion inside it keep working.
   *
   * `outlines` supplies the synthesized outlines for the proto `outlineFile`
   * was generated from; it is evaluated only when the file is actually missing.
   * No-op when the file still exists or no matching outline is found.
   */
  def regenerateIfMissing(
      workspace: AbsolutePath,
      outlineFile: AbsolutePath,
      protoPath: AbsolutePath,
      outlines: => Seq[VirtualTextDocument],
  ): Unit =
    if (!outlineFile.exists) {
      val className = outlineFile.filename.stripSuffix(".java")
      outlines
        .find(outline =>
          ProtoJavaVirtualFile
            .extractClassName(outline.uri().toString())
            .contains(className)
        )
        .foreach(outline =>
          materialize(workspace, protoPath, className, outline.text)
        )
    }

  private def writeIfChanged(file: AbsolutePath, content: String): Unit = {
    val changed =
      !file.exists || MD5.compute(file.toNIO) != MD5.compute(content)
    if (changed) {
      Files.createDirectories(file.toNIO.getParent)
      Files.write(file.toNIO, content.getBytes(StandardCharsets.UTF_8))
    }
  }
}
