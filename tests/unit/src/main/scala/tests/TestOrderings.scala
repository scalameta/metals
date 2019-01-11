package tests

import org.eclipse.{lsp4j => l}

object TestOrderings {
  implicit val lspRange: Ordering[l.Range] = new Ordering[l.Range] {
    override def compare(a: l.Range, b: l.Range): Int = {
      val byLine = Integer.compare(
        a.getStart.getLine,
        b.getStart.getLine
      )
      if (byLine != 0) {
        byLine
      } else {
        val byCharacter = Integer.compare(
          a.getStart.getCharacter,
          b.getStart.getCharacter
        )
        byCharacter
      }
    }
  }
}
