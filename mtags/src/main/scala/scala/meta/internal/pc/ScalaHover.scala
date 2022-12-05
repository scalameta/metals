package scala.meta.internal.pc

import java.util.Optional

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.HoverSignature

import org.eclipse.lsp4j

case class ScalaHover(
    expressionType: Option[String] = None,
    symbolSignature: Option[String] = None,
    docstring: Option[String] = None,
    forceExpressionType: Boolean = false,
    range: Option[lsp4j.Range] = None
) extends HoverSignature {

  def signature(): Optional[String] = symbolSignature.asJava
  def toLsp(): lsp4j.Hover = {
    val markdown =
      HoverMarkup(
        expressionType.getOrElse(""),
        symbolSignature,
        docstring.getOrElse(""),
        forceExpressionType
      )
    val hover = new lsp4j.Hover(markdown.toMarkupContent)

    range.foreach(hover.setRange)

    hover
  }

  def getRange(): Optional[lsp4j.Range] = range.asJava

  def withRange(range: lsp4j.Range): HoverSignature =
    ScalaHover(
      symbolSignature,
      expressionType,
      docstring,
      forceExpressionType,
      Some(range)
    )

}
