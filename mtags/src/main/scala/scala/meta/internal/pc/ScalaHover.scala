package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._

import scala.meta.pc.HoverSignature

import org.eclipse.lsp4j
import java.util.Optional

case class ScalaHoverInfo(
    expressionType: String,
    symbolSignature: String,
    docstring: String,
    forceExpressionType: Boolean = false
)

case class ScalaHover(
    info: Either[String, ScalaHoverInfo],
    range: Option[lsp4j.Range] = None
) extends HoverSignature {

  def toLsp(): lsp4j.Hover = {
    val markdown =
      info match {
        case Left(str) => HoverMarkup(str)
        case Right(info) =>
          HoverMarkup(
            info.expressionType,
            info.symbolSignature,
            info.docstring,
            info.forceExpressionType
          )
      }
    val hover = new lsp4j.Hover(markdown.toMarkupContent)

    range.foreach(hover.setRange)

    hover
  }

  def signature(): Optional[String] =
    info match {
      case Left(_) => Optional.empty()
      case Right(info) => Optional.of(info.symbolSignature)
    }

  def getRange(): Optional[lsp4j.Range] = range.asJava

  def withRange(range: lsp4j.Range): HoverSignature = ScalaHover(info, Some(range))

}

object ScalaHover {
  def fromInfo(
      expressionType: String,
      symbolSignature: String,
      docstring: String,
      forceExpressionType: Boolean = false,
      range: Option[lsp4j.Range] = None
  ): ScalaHover =
    ScalaHover(
      Right(
        ScalaHoverInfo(
          expressionType,
          symbolSignature,
          docstring,
          forceExpressionType
        )
      ),
      range
    )
}
