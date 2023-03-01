package scala.meta.internal.pc

import org.eclipse.{lsp4j => l}

trait PcValReferenceProvider {
  val text: Array[Char]
  val position: l.Position
  def result(): List[Occurrence]
  def defAndRefs(): Option[(Definition, List[Reference])] = {
    val allOccurences = result()
    val references: List[Reference] = allOccurences.collect {
      case v: Reference => v
    }
    val defs: List[Definition] = allOccurences.collect { case d: Definition =>
      d
    }

    defs match {
      case d :: Nil => Some(d, references)
      case _ => None
    }
  }
}

case class RangeOffset(start: Int, end: Int)

sealed trait Occurrence

case class Definition(
    termNameRange: l.Range,
    range: l.Range,
    rhs: String,
    rangeOffsets: RangeOffset,
    isLocal: Boolean,
) extends Occurrence

case class Reference(range: l.Range, parentOffsets: Option[RangeOffset])
    extends Occurrence
