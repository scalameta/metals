package scala.meta.internal.jpc

import java.util.Optional

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.HoverMarkup
import scala.meta.pc.ContentType
import scala.meta.pc.ContentType.MARKDOWN
import scala.meta.pc.HoverSignature

import org.eclipse.lsp4j

case class JavaHover(
    expressionType: Option[String] = None,
    symbolSignature: Option[String] = None,
    docstring: Option[String] = None,
    forceExpressionType: Boolean = false,
    range: Option[lsp4j.Range] = None,
    override val contentType: ContentType
) extends HoverSignature {

  def signature(): Optional[String] = symbolSignature.asJava

  override def toLsp(): lsp4j.Hover = {
    val markup = HoverMarkup.javaHoverMarkup(
      expressionType.getOrElse(""),
      symbolSignature.getOrElse(""),
      docstring.getOrElse(""),
      forceExpressionType,
      markdown = contentType == MARKDOWN
    )
    new lsp4j.Hover(markup.toMarkupContent(contentType), range.orNull)
  }

  def getRange(): Optional[lsp4j.Range] = range.asJava

  def withRange(range: lsp4j.Range): HoverSignature =
    copy(range = Some(range))

}
