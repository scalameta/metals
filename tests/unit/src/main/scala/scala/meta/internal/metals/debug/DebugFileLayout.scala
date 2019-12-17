package scala.meta.internal.metals.debug

import scala.meta.inputs.Input
import scala.meta.inputs.Position

final case class DebugFileLayout(
    relativePath: String,
    content: String,
    breakpoints: List[Position]
) {
  override def toString: String =
    s"""|/$relativePath
        |$content
        |""".stripMargin
}

object DebugFileLayout {
  def apply(name: String, originalText: String): DebugFileLayout = {
    val text = originalText.replaceAll(">>", "  ")
    val breakpoints = ">>".r
      .findAllMatchIn(originalText)
      .map(_.start)
      .map(offset => Position.Range(Input.String(text), offset, offset))

    DebugFileLayout(name, text, breakpoints.toList)
  }
}
