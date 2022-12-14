package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

trait ReferenceProvider {
  val text: Array[Char]
  val range: l.Range
  def result(): List[Either[Reference, Definition]]
}

case class Definition(
    range: l.Range,
    rhs: String,
    rangeOffsets: (Int, Int),
    isLocal: Boolean
)

case class Reference(range: l.Range, parentOffsets: Option[(Int, Int)])
