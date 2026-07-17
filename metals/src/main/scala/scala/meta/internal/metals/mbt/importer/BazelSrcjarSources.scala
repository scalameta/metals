package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import java.{util => ju}

import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtNamespace
import scala.meta.internal.metals.mbt.OID
import scala.meta.io.AbsolutePath

/** Materializes `.srcjar` sources of Bazel MBT namespaces. */
object BazelSrcjarSources {

  private val extractionRoot = ".metals/mbt-srcjar-sources"

  private def isSrcjar(source: String): Boolean = source.endsWith(".srcjar")

  def isScalaBearingSource(source: String): Boolean =
    source.endsWith(".scala") || isSrcjar(source)

  /**
   * Rewrite every namespace, replacing each `.srcjar` source entry with its
   * extraction directory.
   */
  def materializeAll(workspace: AbsolutePath, build: MbtBuild): MbtBuild = {
    val keptDirNames = mutable.Set.empty[String]
    val namespaces = new ju.LinkedHashMap[String, MbtNamespace]()
    build.getNamespaces.forEach { (name, namespace) =>
      val sources = namespace.getSources.asScala.toList
      val rewritten = sources.map { source =>
        if (isSrcjar(source)) {
          keptDirNames += sanitize(source)
          materialize(workspace, source).getOrElse(source)
        } else source
      }
      val updated =
        if (rewritten == sources) namespace
        else namespace.copy(sources = rewritten.asJava)
      namespaces.put(name, updated)
    }
    if (!build.isEmpty) pruneStaleExtractions(workspace, keptDirNames.toSet)
    build.copy(namespaces = namespaces)
  }

  private def pruneStaleExtractions(
      workspace: AbsolutePath,
      keptDirNames: Set[String],
  ): Unit = {
    val root = workspace.resolve(extractionRoot).toNIO
    if (Files.isDirectory(root)) {
      val stale =
        Using(Files.list(root))(
          _.iterator().asScala
            .filterNot(dir => keptDirNames(dir.getFileName.toString))
            .toList
        ).getOrElse(Nil)
      stale.foreach(dir => RecursivelyDelete(AbsolutePath(dir)))
    }
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
    if (srcjar.isFile)
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
    else None
  }

  // Path separators fold `a/b.srcjar` and `a_b.srcjar` to the same name;
  // the hash suffix keeps them distinct.
  private def sanitize(path: String): String = {
    val readable = path.replaceAll("""[/\\:]""", "_")
    s"$readable-${OID.fromText(path).take(12)}"
  }

  private def extract(srcjar: Path, outDir: Path): Unit = {
    // Root must be normalized too, or an un-normalized `.`/`..` segment
    // breaks the zip-slip `startsWith` check below.
    val root = outDir.normalize()
    Files.createDirectories(root)
    Using.resource(new ZipFile(srcjar.toFile)) { zip =>
      for {
        entry <- zip.entries().asScala
        name = entry.getName
        if !entry.isDirectory
        if name.endsWith(".scala") || name.endsWith(".java")
        target = root.resolve(name).normalize()
        // A zip entry must not escape the extraction directory (zip slip).
        if target.startsWith(root)
      } {
        Files.createDirectories(target.getParent)
        Using.resource(zip.getInputStream(entry)) { in =>
          Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }
}
