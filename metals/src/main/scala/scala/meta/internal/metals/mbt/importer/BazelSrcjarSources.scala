package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.{util => ju}

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtNamespace
import scala.meta.internal.metals.mbt.OID
import scala.meta.io.AbsolutePath

/**
 * Materializes `.srcjar` sources of Bazel MBT namespaces.
 *
 * A Bazel target with `srcs = ["lib.srcjar"]` compiles the files INSIDE the
 * jar as its own sources, but a jar path in an MBT namespace's `sources` is
 * useless to the presentation compiler and the indexer — nothing inside it
 * is typable or navigable. Extract each srcjar's `.scala`/`.java` entries
 * into generated state under `.metals/mbt-srcjar-sources/<sanitized path>/`
 * and list that directory as the namespace source instead, so the extracted
 * files belong to the namespace exactly like ordinary sources.
 *
 * Extraction is idempotent per srcjar content (a digest marker of
 * size+mtime); zip entries escaping the extraction directory are skipped.
 */
object BazelSrcjarSources {

  private val extractionRoot = ".metals/mbt-srcjar-sources"

  def isSrcjar(source: String): Boolean = source.endsWith(".srcjar")

  /**
   * Rewrite every namespace, replacing each `.srcjar` source entry with its
   * extraction directory. Srcjars that cannot be read or extracted keep
   * their original entry (an import is strictly better with materialized
   * sources but must not fail without them).
   */
  def materializeAll(workspace: AbsolutePath, build: MbtBuild): MbtBuild = {
    val namespaces = new ju.LinkedHashMap[String, MbtNamespace]()
    build.getNamespaces.forEach { (name, namespace) =>
      val sources = namespace.getSources.asScala.toList
      val rewritten = sources.map { source =>
        if (isSrcjar(source))
          materialize(workspace, source).getOrElse(source)
        else source
      }
      val updated =
        if (rewritten == sources) namespace
        else namespace.copy(sources = rewritten.asJava)
      namespaces.put(name, updated)
    }
    build.copy(namespaces = namespaces)
  }

  /**
   * Extract one srcjar (workspace-relative path) and return the
   * workspace-relative extraction directory.
   */
  def materialize(
      workspace: AbsolutePath,
      srcjarPath: String,
  ): Option[String] = {
    val srcjar = workspace.resolve(srcjarPath)
    if (!srcjar.isFile) None
    else
      try {
        val relativeDir = s"$extractionRoot/${sanitize(srcjarPath)}"
        val outDir = workspace.resolve(relativeDir)
        val marker = outDir.resolve(".extracted").toNIO
        val digest =
          s"${Files.size(srcjar.toNIO)}:" +
            s"${Files.getLastModifiedTime(srcjar.toNIO).toMillis}"
        val upToDate =
          Files.isRegularFile(marker) && Files.readString(marker) == digest
        if (!upToDate) {
          RecursivelyDelete(outDir)
          extract(srcjar.toNIO, outDir.toNIO)
          Files.writeString(marker, digest)
        }
        Some(relativeDir)
      } catch {
        case NonFatal(error) =>
          scribe.warn(
            s"bazel-mbt: could not extract sources from $srcjarPath: $error"
          )
          None
      }
  }

  // Replacing the path separators with `_` is not injective on its own
  // (`a/b.srcjar` and `a_b.srcjar` both fold to `a_b.srcjar`), which would make
  // two distinct srcjars share — and clobber — one extraction directory. Append
  // a short content-addressed hash of the full original path so each srcjar gets
  // a stable, distinct directory while the readable prefix is preserved.
  private def sanitize(path: String): String = {
    val readable = path.replaceAll("""[/\\:]""", "_")
    s"$readable-${OID.fromText(path).take(12)}"
  }

  private def extract(srcjar: Path, outDir: Path): Unit = {
    Files.createDirectories(outDir)
    val zip = new ZipFile(srcjar.toFile)
    try {
      for {
        entry <- zip.entries().asScala
        name = entry.getName
        if !entry.isDirectory
        if name.endsWith(".scala") || name.endsWith(".java")
        target = outDir.resolve(name).normalize()
        // A zip entry must not escape the extraction directory (zip slip).
        if target.startsWith(outDir)
      } {
        Files.createDirectories(target.getParent)
        val in = zip.getInputStream(entry)
        try Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
        finally in.close()
      }
    } finally zip.close()
  }
}
