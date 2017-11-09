package langserver.core

import org.scalatest.FunSuite
import langserver.types.Position

class TextDocumentSuite extends FunSuite {
  test("correct one line doc") {
    val text = """one line document"""

    val td = new TextDocument("file:///dummy.txt", text.toArray)

    assert(td.offsetToPosition(0) == Position(0, 0))
    assert(td.offsetToPosition(3) == Position(0, 3))
    assert(td.offsetToPosition(16) == Position(0, 16))

    assert(td.positionToOffset(Position(0, 0)) == 0)
    assert(td.positionToOffset(Position(0, 3)) == 3)
    assert(td.positionToOffset(Position(0, 16)) == 16)
  }

    test("correct two lines doc") {
    val text = """line1
line2
"""

    val td = new TextDocument("file:///dummy.txt", text.toArray)

    assert(td.offsetToPosition(0) == Position(0, 0))
    assert(td.offsetToPosition(4) == Position(0, 4))
    assert(td.offsetToPosition(5) == Position(0, 5)) // exactly the new line character
    assert(td.offsetToPosition(6) == Position(1, 0))
    assert(td.offsetToPosition(7) == Position(1, 1))
    assert(td.offsetToPosition(10) == Position(1, 4))
    assert(td.offsetToPosition(11) == Position(1, 5))

    assert(td.positionToOffset(Position(0, 0)) == 0)
    assert(td.positionToOffset(Position(0, 4)) == 4)
    assert(td.positionToOffset(Position(1, 0)) == 6)
    assert(td.positionToOffset(Position(1, 4)) == 10)
    assert(td.positionToOffset(Position(1, 5)) == 11)
  }

  test("several lines CR/LF") {
    val text = "line1\r\nline2\r\n"

    val td = new TextDocument("file:///dummy.txt", text.toArray)

    assert(td.offsetToPosition(0) == Position(0, 0))
    assert(td.offsetToPosition(4) == Position(0, 4))
    assert(td.offsetToPosition(5) == Position(0, 5)) // exactly the new line character
    assert(td.offsetToPosition(7) == Position(1, 0))
    assert(td.offsetToPosition(8) == Position(1, 1))
    assert(td.offsetToPosition(11) == Position(1, 4))
    assert(td.offsetToPosition(12) == Position(1, 5))

    assert(td.positionToOffset(Position(0, 0)) == 0)
    assert(td.positionToOffset(Position(0, 4)) == 4)
    assert(td.positionToOffset(Position(1, 0)) == 7)
    assert(td.positionToOffset(Position(1, 4)) == 11)
    assert(td.positionToOffset(Position(1, 5)) == 12)
  }
}
