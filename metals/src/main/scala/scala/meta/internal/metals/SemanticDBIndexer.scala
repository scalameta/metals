package scala.meta.internal.metals

import java.nio.file.Path
import MetalsEnrichments._
import java.nio.file.Files
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import scala.meta.internal.semanticdb.TextDocuments

class SemanticDBIndexer(
    referenceProvider: ReferenceProvider,
    implementationProvider: ImplementationProvider
) {

  def onScalacOptions(scalacOptions: ScalacOptionsResult): Unit = {
    for {
      item <- scalacOptions.getItems.asScala
    } {
      val targetroot = item.targetroot
      onChangeDirectory(targetroot.resolve(Directories.semanticdb).toNIO)
    }
  }

  def reset(): Unit = {
    referenceProvider.reset()
    implementationProvider.clear()
  }

  def onDelete(file: Path): Unit = {
    referenceProvider.onDelete(file)
  }

  /**
   * Handle EventType.OVERFLOW, meaning we lost file events for a given path.
   *
   * We walk up the file tree to the parent `META-INF/semanticdb` parent directory
   * and re-index all of its `*.semanticdb` children.
   */
  def onOverflow(path: Path): Unit = {
    path.semanticdbRoot match {
      case Some(root) =>
        onChangeDirectory(root)
      case None =>
    }
  }

  def onChangeDirectory(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.forEach(file => if (file.toFile().isFile()) onChange(file))
      } finally {
        stream.close()
      }
    }
  }

  def onChange(file: Path): Unit = {
    if (file.isSemanticdb) {
      val doc = TextDocuments.parseFrom(Files.readAllBytes(file))
      referenceProvider.onChange(doc, file)
      implementationProvider.onChange(doc)
    } else {
      scribe.warn(s"not semanticdb file: $file")
    }
  }
}
