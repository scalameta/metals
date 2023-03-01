package scala.meta.internal.pc

import org.eclipse.lsp4j

import java.util.Optional
import scala.meta.pc.HoverSignature
import scala.meta.internal.mtags.CommonMtagsEnrichments._

case class ScalaHover(
    expressionType: Option[String] = None,
    symbolSignature: Option[String] = None,
    docstring: Option[String] = None,
    forceExpressionType: Boolean = false,
    range: Option[lsp4j.Range] = None,
) extends HoverSignature {

  def signature(): Optional[String] = symbolSignature.asJava
  def toLsp(): lsp4j.Hover = {
    val markdown =
      HoverMarkup(
        expressionType.getOrElse(""),
        symbolSignature,
        docstring.getOrElse(""),
        forceExpressionType,
      )
    new lsp4j.Hover(markdown.toMarkupContent, range.orNull)
  }

  def getRange(): Optional[lsp4j.Range] = range.asJava

  def withRange(range: lsp4j.Range): HoverSignature =
    ScalaHover(
      expressionType,
      symbolSignature,
      docstring,
      forceExpressionType,
      Some(range),
    )

}
