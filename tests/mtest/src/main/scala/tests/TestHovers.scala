package tests

import scala.meta.inputs.Input
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.HoverMarkup

import org.eclipse.lsp4j.Hover

object TestHovers extends TestHovers
trait TestHovers {
  implicit class XtensionString(string: String) {
    def hover: String =
      string.trim.linesIterator.toList match {
        case List(symbolSignature) =>
          HoverMarkup("", symbolSignature, "")
        case List(expressionType, symbolSignature) =>
          HoverMarkup(expressionType, symbolSignature, "")
        case _ =>
          string
      }
  }

  def renderAsString(
      code: String,
      hover: Option[Hover],
      includeRange: Boolean
  ): String = {
    hover match {
      case Some(value) =>
        val types = value.getContents.asScala match {
          case Right(value) =>
            value.getValue
          case Left(values) =>
            values.asScala
              .map { e =>
                e.asScala match {
                  case Left(value) =>
                    value
                  case Right(marked) =>
                    codeFence(marked.getValue, marked.getLanguage)
                }
              }
              .mkString("\n")
        }
        val range = Option(value.getRange) match {
          case Some(value) if includeRange =>
            codeFence(
              value.toMeta(Input.String(code)).text,
              "range"
            )
          case _ => ""
        }
        List(types, range).filterNot(_.isEmpty).mkString("\n")
      case None =>
        ""
    }

  }

  private def toScala(hover: Hover) = {
    hover.getContents
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
