package scala.meta.internal.metals

import scala.{meta => m}
import scala.meta.internal.mtags.MtagsEnrichments._

/**
 * File template which allows specifying the cursor position using @@
 */
final case class NewFileTemplate private (template: String) {
  import NewFileTemplate._

  lazy val fileContent: String = template.replaceAllLiterally(cursorMarker, "")

  lazy val cursorPosition: m.Position = {
    val input = m.Input.String(template)
    val offset = template.indexOf(cursorMarker)
    input.toOffsetPosition(offset)
  }

  def map(f: String => String): NewFileTemplate =
    NewFileTemplate(f(template))

}

object NewFileTemplate {
  private val cursorMarker = "@@"

  /**
   * @param template the file template string. Must contain exactly one cursor marker (@@)
   */
  def apply(template: String): NewFileTemplate = {
    val cursorMarkersCount = cursorMarker.r.findAllMatchIn(template).length
    require(
      cursorMarkersCount == 1,
      s"File templates must contain exactly one cursor marker '$cursorMarker'. Found $cursorMarkersCount"
    )
    new NewFileTemplate(template)
  }

  def empty = new NewFileTemplate(cursorMarker)
}
