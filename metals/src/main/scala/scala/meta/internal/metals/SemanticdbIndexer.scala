package scala.meta.internal.metals

import java.nio.file.Path
import MetalsEnrichments._
import java.nio.file.Files
import ch.epfl.scala.bsp4j.ScalacOptionsResult
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.implementation.ImplementationProvider

class SemanticdbIndexer(
    referenceProvider: ReferenceProvider,
    implementationProvider: ImplementationProvider,
    buildTargets: BuildTargets
) {

  def onScalacOptions(scalacOptions: ScalacOptionsResult): Unit = {
    for (item <- scalacOptions.getItems.asScala) {
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
    implementationProvider.onDelete(file)
  }

  /**
   * Handle EventType.OVERFLOW, meaning we lost file events for a given path.
   *
   * We walk up the file tree to the parent `META-INF/semanticdb` parent directory
   * and re-index all of its `*.semanticdb` children.
   */
  def onOverflow(path: Path): Unit = {
    path.semanticdbRoot.foreach(onChangeDirectory(_))
  }

  /**
   * Handle EventType.OVERFLOW, meaning we lost file events, when we don't know the path.
   * We walk up the file tree for all targets `META-INF/semanticdb` directory
   * and re-index all of its `*.semanticdb` children.
   */
  def onOverflow(): Unit = {
    buildTargets.scalacOptions.foreach { item =>
      val targetroot = item.targetroot
      if (!targetroot.isJar) {
        onChangeDirectory(targetroot.resolve(Directories.semanticdb).toNIO)
      }
    }
  }

  def onChangeDirectory(dir: Path): Unit = {
    if (Files.isDirectory(dir)) {
      val stream = Files.walk(dir)
      try {
        stream.forEach(onChange(_))
      } finally {
        stream.close()
      }
    }
  }

  def onChange(file: Path): Unit = {
    if (!Files.isDirectory(file)) {
      if (file.isSemanticdb) {
        val doc = TextDocuments.parseFrom(Files.readAllBytes(file))
        referenceProvider.onChange(doc, file)
        implementationProvider.onChange(doc, file)
      } else {
        scribe.warn(s"not a semanticdb file: $file")
      }
    }
  }
}
