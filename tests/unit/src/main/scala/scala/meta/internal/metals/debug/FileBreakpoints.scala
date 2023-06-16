package scala.meta.internal.metals.debug

import scala.meta.io.AbsolutePath

sealed trait FileBreakpoints {
  def breakpoints: List[Int]
  def path: AbsolutePath
}

final case class LocalFileBreakpoints(
    root: AbsolutePath,
    relativePath: String,
    content: String,
    breakpoints: List[Int],
) extends FileBreakpoints {

  override def path: AbsolutePath = root.resolve(relativePath)
  override def toString: String =
    s"""|/$relativePath
        |$content
        |""".stripMargin
}

final case class LibraryBreakpoints(path: AbsolutePath, breakpoints: List[Int])
    extends FileBreakpoints

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
}
