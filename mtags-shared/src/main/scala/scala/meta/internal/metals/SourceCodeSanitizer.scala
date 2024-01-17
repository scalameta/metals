package scala.meta.internal.metals

import java.util.regex.Pattern

import scala.meta.internal.metals.SourceCodeSanitizer.Language

/**
 * Sanitizer ensuring that no original source code can leak through the reports.
 * First it would treat input as the markdown source snippet with 1 or more code snipets.
 * If the snippet contains parsable code it would erase all the original names, replacing them with synthetic symbols of the same length.
 * If the code is not parsable or the transformed code is would not be parsable after transformation it would be replaced with an failure reason tag.
 * If no code snipets are found the input is treated as a raw source code.
 */
class SourceCodeSanitizer[ParserCtx, ParserAST](
    parser: SourceCodeTransformer[ParserCtx, ParserAST]
) extends ReportSanitizer {

  override def sanitize(text: String): String = {
    anonimizeMarkdownSnippets(text)
      .getOrElse(tryAnonimize(text, languageHint = Some(Language.Scala)).merge)
  }

  private final val OffsetMarker = "@@"

  private final val MarkdownCodeSnippet = java.util.regex.Pattern
    .compile(
      raw"```(\w+)?\s*\R([\s\S]*?)```",
      Pattern.MULTILINE | Pattern.CASE_INSENSITIVE
    )
  private final val StackTraceLine =
    raw"(?:\s*(?:at\s*))?(\S+)\((?:(?:\S+\.(?:scala|java)\:\d+)|(?:Native Method))\)".r

  private type FailureReason = String
  private def tryAnonimize(
      source: String,
      languageHint: Option[Language]
  ): Either[FailureReason, String] = {
    Option(source)
      .map(_.trim())
      .filter(_.nonEmpty)
      .map(_.replaceAll(OffsetMarker, ""))
      .fold[Either[String, String]](Left("no-source")) { source =>
        if (StackTraceLine.findFirstIn(source).isDefined)
          Right(source)
        else if (languageHint.forall(_ == Language.Scala)) {
          parser
            .parse(source)
            .toRight("<unparsable>")
            .flatMap { case (ctx, tree) =>
              parser.transformer
                .sanitizeSymbols(tree)
                .toRight("<ast-transformation-failed>")
                .flatMap { parsed =>
                  val sourceString = parser.toSourceString(parsed, ctx)
                  val isReparsable = parser.parse(sourceString, ctx).isDefined
                  if (isReparsable) Right(sourceString)
                  else Left("<invalid-transformation-not-reparsable>")
                }
            }
        } else
          Left("<unknown-source-redacted-out>")
      }
  }

  private def anonimizeMarkdownSnippets(source: String): Option[String] = {
    // Check if we have even number of markdown snipets markers, if not discard whole input
    val snipetMarkers = source.linesIterator.count(_.startsWith("```"))
    if (snipetMarkers == 0 || snipetMarkers % 2 != 0) None
    else {
      val matcher = MarkdownCodeSnippet.matcher(source)
      val sourceResult = new java.lang.StringBuffer(source.size)
      while (matcher.find()) {
        val matchResult = matcher.toMatchResult()
        val language =
          Option(matchResult.group(1)).map(_.trim()).flatMap(Language.unapply)
        val result = tryAnonimize(
          languageHint = language,
          source = matchResult.group(2)
        )
        val sanitizedOrFailureReason: String = result.merge.replace("$", "\\$")
        val updatedSnippet =
          s"""```${language.map(_.stringValue).getOrElse("")}
             |$sanitizedOrFailureReason
             |```
             |""".stripMargin

        matcher.appendReplacement(
          sourceResult,
          updatedSnippet
        )
      }
      if (sourceResult.length() == 0) None // not found any snipets
      else
        Some {
          matcher.appendTail(sourceResult)
          sourceResult.toString()
        }
    }
  }
}

object SourceCodeSanitizer {
  sealed abstract class Language(val stringValue: String)
  object Language {
    def unapply(v: String): Option[Language] = v.toLowerCase() match {
      case "scala" => Some(Scala)
      case "java" => Some(Java)
      case _ => None
    }
    case object Scala extends Language("scala")
    case object Java extends Language("java")
  }
}
