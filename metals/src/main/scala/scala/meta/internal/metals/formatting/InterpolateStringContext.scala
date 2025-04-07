package scala.meta.internal.metals.formatting

import scala.meta

import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.tokens.Token

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit

/**
 * Formatter that automatically converts a string literal to an interpolation
 * when an interpolation `${}` marker is added in the literal.
 */
object InterpolateStringContext extends OnTypeFormatter {

  override def contribute(
      params: OnTypeFormatterParams
  ): Option[List[TextEdit]] = {
    params.triggerChar.head match {
      case '{' => contributeInterpolationContext(params)
      case _ => None
    }
  }

  private def contributeInterpolationContext(
      params: OnTypeFormatterParams
  ): Option[List[TextEdit]] = {
    val range = new Range(params.position, params.position)
    params.tokens
      .getOrElse(Nil)
      .collectFirst {
        case lit: Token.Constant.String if lit.pos.toLsp.encloses(range) =>
          convertStringToInterpolation(
            lit.pos,
            params.sourceText,
            params.startPos.start,
          )
      }
      .flatten
  }

  private def convertStringToInterpolation(
      stringLitPos: meta.Position,
      sourceText: String,
      cursorPos: Int,
  ): Option[List[TextEdit]] = {
    val expectedDollarPos = cursorPos - 2
    val input = stringLitPos.input
    if (
      expectedDollarPos >= 0 && cursorPos < sourceText.length() && sourceText
        .substring(expectedDollarPos, cursorPos + 1) == "${}"
    ) {
      def insertTextEdit(offset: Int, text: String): TextEdit =
        new TextEdit(Position.Range(input, offset, offset).toLsp, text)

      val interpolationContextEdit = insertTextEdit(stringLitPos.start, "s")
      val dollarEdits = (stringLitPos.start to stringLitPos.end)
        .filter(i => sourceText.charAt(i) == '$' && i != expectedDollarPos)
        .map(insertTextEdit(_, "$"))
        .toList

      Some(interpolationContextEdit :: dollarEdits.toList)
    } else {
      None
    }
  }
}
