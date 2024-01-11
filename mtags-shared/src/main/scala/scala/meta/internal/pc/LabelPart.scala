package scala.meta.internal.pc
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.Position

case class LabelPart(
    label: InlayHintLabelPart,
    data: Either[String, Position]
) {
  def name: String = label.getValue()
  def length: Int = name.length
}

object LabelPart {
  def apply(
      label: String,
      symbol: String = "",
      pos: Option[Position] = None
  ): LabelPart = {
    val labelPart = new InlayHintLabelPart(label)
    pos match {
      case None => LabelPart(labelPart, Left(symbol))
      case Some(pos) => LabelPart(labelPart, Right(pos))
    }
  }

  implicit class XtensionLabelParts(parts: List[List[LabelPart]]) {
    def mkLabel(separator: String): List[LabelPart] = {
      parts match {
        case Nil => Nil
        case head :: tail =>
          head ::: tail.flatMap(LabelPart(separator) :: _)
      }
    }
    
    def mkLabel(
        start: String,
        separator: String,
        end: String
    ): List[LabelPart] =
      (LabelPart(start) :: parts.mkLabel(separator)) ::: List(LabelPart(end))
  }
}
