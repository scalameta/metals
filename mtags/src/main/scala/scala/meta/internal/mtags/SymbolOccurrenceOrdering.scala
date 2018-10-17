package scala.meta.internal.mtags
import scala.meta.internal.semanticdb.SymbolOccurrence

object SymbolOccurrenceOrdering {

  implicit val occurrenceOrdering: Ordering[SymbolOccurrence] =
    new Ordering[SymbolOccurrence] {
      override def compare(x: SymbolOccurrence, y: SymbolOccurrence): Int = {
        if (x.range.isEmpty) 0
        else if (y.range.isEmpty) 0
        else {
          val a = x.range.get
          val b = y.range.get
          val byLine = Integer.compare(
            a.startLine,
            b.startLine
          )
          if (byLine != 0) {
            byLine
          } else {
            val byCharacter = Integer.compare(
              a.startCharacter,
              b.startCharacter
            )
            byCharacter
          }
        }
      }
    }

}
