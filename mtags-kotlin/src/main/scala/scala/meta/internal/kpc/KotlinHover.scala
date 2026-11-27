package scala.meta.internal.kpc

import java.util.Optional

import scala.jdk.OptionConverters._

import scala.meta.pc.ContentType
import scala.meta.pc.HoverSignature

import org.eclipse.lsp4j

case class KotlinHover(
    expressionType: Option[String],
    symbolSignature: Option[String],
    range: Option[lsp4j.Range],
    override val contentType: ContentType
) extends HoverSignature {

  def signature(): Optional[String] = symbolSignature.toJava

  def getRange(): Optional[lsp4j.Range] = range.toJava

  def withRange(range: lsp4j.Range): HoverSignature =
    copy(range = Some(range))

  def toLsp(): lsp4j.Hover = {
    val markdown = contentType == ContentType.MARKDOWN
    def code(text: String): String =
      if (markdown) s"```kotlin\n$text\n```" else text
    // Show the expression type only when it adds to the signature.
    val typeBlock = expressionType
      .filterNot(tpe => symbolSignature.exists(_.endsWith(s": $tpe")))
      .map(tpe => code(tpe))
    val text = (typeBlock.toList ++ symbolSignature.map(code)).mkString("\n")
    val kind =
      if (markdown) lsp4j.MarkupKind.MARKDOWN else lsp4j.MarkupKind.PLAINTEXT
    new lsp4j.Hover(new lsp4j.MarkupContent(kind, text), range.orNull)
  }
}
