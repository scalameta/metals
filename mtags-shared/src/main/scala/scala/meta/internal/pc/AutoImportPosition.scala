package scala.meta.internal.pc

import scala.annotation.tailrec

case class ImportPosition(
    offset: Int,
    padBefore: String,
    padAfter: String,
    indent: Int = 0
)

/**
 * A position to insert new imports
 *
 * @param offset the offset where to place the import.
 * @param indent the indentation at which to place the import.
 * @param padTop whether the import needs to be padded on top
 *               in the case that it is the first one after the package def
 */
case class AutoImportPosition(
    findPosition: String => ImportPosition,
    indent: Option[String],
    lastImportIndex: Int
) {

  // def this(offset: Int, text: String, padTop: Boolean) =
  //   this(offset, AutoImportPosition.inferIndent(offset, text), padTop) // TODO
}

object AutoImportPosition {

  def empty: AutoImportPosition = AutoImportPosition(
    _ => ImportPosition(0, "\n", "\n"),
    indent = None,
    0
  )

  private val endOfLineCharacters = Set('\r', '\n')

  // Infers the indentation at the completion position by counting the number of leading
  // spaces in the line.
  // For example:
  // class Main {
  //   def foo<COMPLETE> // inferred indent is 2 spaces.
  // }
  @tailrec
  def inferIndent(lineStart: Int, text: String): Int = {
    var i = 0
    while (lineStart + i < text.length && text.charAt(lineStart + i) == ' ') {
      i += 1
    }

    val pos = lineStart + i
    if (pos < text.length() && endOfLineCharacters(text.charAt(pos))) {
      // skip any empty lines
      inferIndent(pos + 1, text)
    } else i
  }
}
