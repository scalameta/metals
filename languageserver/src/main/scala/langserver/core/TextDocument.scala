package langserver.core

import langserver.types.TextDocumentIdentifier
import langserver.types.TextDocumentContentChangeEvent
import langserver.types.Position

case class TextDocument(uri: String, contents: Array[Char]) {
  def applyChanges(changes: Seq[TextDocumentContentChangeEvent]): TextDocument = {
    // we assume full text sync
    assert(changes.size == 1)
    val change = changes.head
    assert(change.range.isEmpty)
    assert(change.rangeLength.isEmpty)

    copy(contents = change.text.toArray)
  }

  private def peek(idx: Int) =
    if (idx < contents.size) contents(idx) else -1

  /**
   * Return the corresponding position in this text document as 0-based line and column.
   */
  def offsetToPosition(offset: Int): Position = {
    if (offset >= contents.size)
      throw new IndexOutOfBoundsException(s"$uri: asked position at offset $offset, but contents is only ${contents.size} characters long.")

    var i, line, col = 0

    while (i < offset) {
      contents(i) match {
        case '\r' =>
          line += 1
          col = 0
          if (peek(i + 1) == '\n') i += 1

        case '\n' =>
          line += 1
          col = 0

        case _ =>
          col += 1
      }
      i += 1
    }

    Position(line, col)
  }

  /**
   * Return the offset in the current document, for a given 0-based line/col position.
   */
  def positionToOffset(pos: Position): Int = {
    val Position(line, col) = pos

    var i, l, c = 0
    while (i < contents.size && l < line) {
      contents(i) match {
        case '\r' =>
          l += 1
          if (peek(i + 1) == '\n') i += 1

        case '\n' =>
          l += 1

        case _ =>
      }
      i += 1
    }

    if (l < line)
      throw new IllegalArgumentException(s"$uri: Can't find position $pos in contents of only $l lines long.")
    if (i + col < contents.size)
      i + col
    else
      throw new IllegalArgumentException(s"$uri: Invalid column. Position $pos in line '${contents.slice(i, contents.size).mkString}'")
  }
}
