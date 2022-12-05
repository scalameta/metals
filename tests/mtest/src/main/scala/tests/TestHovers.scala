package tests

import scala.meta.inputs.Input
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.HoverMarkup

import org.eclipse.lsp4j.Hover

object TestHovers extends TestHovers
trait TestHovers {
  implicit class XtensionString(string: String) {
    def hover: String = {
      string.trim.linesIterator.toList match {
        case List(symbolSignature) =>
          HoverMarkup("", Some(symbolSignature), "")
        case List(expressionType, symbolSignature) =>
          HoverMarkup(
            expressionType,
            Some(symbolSignature),
            "",
            forceExpressionType = true,
          )
        case _ =>
          string
      }
    }

    def hoverRange: String =
      string.trim.linesIterator.toList match {
        case List(symbolSignature) =>
          HoverMarkup(symbolSignature)
        case List(expressionType, symbolSignature) =>
          HoverMarkup(
            expressionType,
            Some(symbolSignature),
            "",
            forceExpressionType = true,
          )
        case _ =>
          string
      }
  }

  def renderAsString(
      code: String,
      hover: Option[Hover],
      includeRange: Boolean,
  ): String = {
    hover match {
      case Some(value) =>
        val types = value.getContents.getRight.getValue()

        val range = Option(value.getRange) match {
          case Some(value) if includeRange =>
            val input = Input.String(code)
            val pos = value
              .toMeta(input)
              .getOrElse(
                throw new RuntimeException(s"$value was not contained in file")
              )
            codeFence(
              pos.text,
              "range",
            )
          case _ => ""
        }
        List(types, range).filterNot(_.isEmpty).mkString("\n")
      case None =>
        ""
    }

  }

  private def codeFence(code: String, language: String): String = {
    val trimmed = code.trim
    if (trimmed.isEmpty) ""
    else {
      new StringBuilder()
        .append("```")
        .append(language)
        .append("\n")
        .append(trimmed)
        .append("\n")
        .append("```")
        .toString()
    }
  }
}
