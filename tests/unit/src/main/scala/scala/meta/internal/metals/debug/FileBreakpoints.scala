package scala.meta.internal.metals.debug

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.debug.Source

sealed trait FileBreakpoints {
  def breakpoints: List[Int]
  def sourceName: String
  def sourcePath: String

  def source: Source = {
    val source = new Source
    source.setName(sourceName)
    source.setPath(sourcePath)
    source
  }
}

final case class LocalFileBreakpoints(
    root: AbsolutePath,
    relativePath: String,
    content: String,
    breakpoints: List[Int],
) extends FileBreakpoints {

  private def path: AbsolutePath = root.resolve(relativePath)
  override def sourceName: String = path.filename
  override def sourcePath: String = path.toString
  override def toString: String =
    s"""|/$relativePath
        |$content
        |""".stripMargin
}

final case class LibraryBreakpoints(
    sourceName: String,
    sourcePath: String,
    breakpoints: List[Int],
) extends FileBreakpoints

object FileBreakpoints {
  def apply(
      name: String,
      originalText: String,
      root: AbsolutePath,
  ): FileBreakpoints = {
    val text = originalText.replaceAll(">>", "  ")
    val breakpoints =
      originalText.linesIterator.zipWithIndex.flatMap { case (line, index) =>
        if (line.trim().startsWith(">>")) Some(index)
        else None
      }.toList

    LocalFileBreakpoints(root, name, text, breakpoints)
  }

  def apply(path: AbsolutePath, breakpoints: List[Int]): LibraryBreakpoints = {
    val sourcePath =
      if (path.isJarFileSystem) path.toURI.toString
      else path.toString
    LibraryBreakpoints(path.filename, sourcePath, breakpoints)
  }
}
