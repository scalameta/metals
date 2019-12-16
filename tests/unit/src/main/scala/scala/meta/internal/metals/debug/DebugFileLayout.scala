package scala.meta.internal.metals.debug

import scala.meta.inputs.Position
import scala.meta.inputs.Input

final case class DebugFileLayout(
    relativePath: String,
    content: String,
    breakpoints: List[Position]
) {
  def layout: String =
    s"""|/$relativePath
        |$content
        |""".stripMargin
}

object DebugFileLayout {
  def apply(layout: String): DebugFileLayout = {
    val (name, originalContent) = splitAtFirstNewLine(layout)
    val text = originalContent.replaceAllLiterally(">>", "  ")
    val breakpoints = ">>".r
      .findAllMatchIn(originalContent)
      .map(_.start)
      .map(offset => Position.Range(Input.String(text), offset, offset))

    DebugFileLayout(name.stripPrefix("/"), text, breakpoints.toList)
  }

  private def splitAtFirstNewLine(text: String): (String, String) = {
    val offset = text.indexOf(System.lineSeparator)
    val prefix = text.substring(0, offset)
    val suffix = text.substring(offset + System.lineSeparator.length)
    (prefix, suffix)
  }
}
