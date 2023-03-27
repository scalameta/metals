package scala.meta.internal.pc

/**
 * A position to insert new imports
 *
 * @param offset the offset where to place the import.
 * @param indent the indentation at which to place the import.
 * @param padTop whether the import needs to be padded on top
 *               in the case that it is the first one after the package def
 */
case class AutoImportPosition(
    offset: Int,
    indent: Int,
    padTop: Boolean
) {

  def this(offset: Int, text: String, padTop: Boolean) =
    this(offset, AutoImportPosition.inferIndent(offset, text), padTop)
}

object AutoImportPosition {

  // Infers the indentation at the completion position by counting the number of leading
  // spaces in the line.
  // For example:
  // class Main {
  //   def foo<COMPLETE> // inferred indent is 2 spaces.
  // }
  def inferIndent(lineStart: Int, text: String): Int = {
    var i = 0
    while (lineStart + i < text.length && text.charAt(lineStart + i) == ' ') {
      i += 1
    }
    i
  }
}
