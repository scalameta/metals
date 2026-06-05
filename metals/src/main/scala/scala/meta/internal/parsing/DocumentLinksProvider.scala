package scala.meta.internal.parsing

import java.net.URLDecoder
import java.util

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import org.eclipse.lsp4j.DocumentLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

final class DocumentLinksProvider(
    buffers: Buffers
) {

  // Matches http/https URLs anywhere in a line
  private val urlRegex = raw"https?://\S+".r

  // Matches {@link Ref} or {@linkplain Ref#method label}
  private val javadocLinkTagRegex = raw"\{@link(?:plain)?\s+([^\s}]+)".r

  // Matches @see Ref or @see Ref#method
  private val javadocSeeTagRegex = raw"@see\s+(\S+)".r

  def getLinks(
      filePath: AbsolutePath
  ): util.List[DocumentLink] =
    buffers
      .get(filePath)
      .fold[util.List[DocumentLink]](util.Collections.emptyList()) { content =>
        val isJava = filePath.isJava
        val fileUri = filePath.toURI.toString
        content.linesIterator.zipWithIndex
          .flatMap { case (line, lineNo) =>
            linksForLine(line, lineNo, fileUri, isJava)
          }
          .toSeq
          .asJava
      }

  private def linksForLine(
      line: String,
      lineNo: Int,
      fileUri: String,
      isJava: Boolean,
  ): Seq[DocumentLink] = {
    val urlLinks =
      urlRegex.findAllMatchIn(line).map(makeUrlLink(lineNo, _)).toSeq
    val javadocLinks =
      if (isJava) javadocLinksForLine(line, lineNo, fileUri)
      else Seq.empty
    urlLinks ++ javadocLinks
  }

  private def javadocLinksForLine(
      line: String,
      lineNo: Int,
      fileUri: String,
  ): Seq[DocumentLink] = {
    val linkTagLinks = javadocLinkTagRegex
      .findAllMatchIn(line)
      .collect {
        case m if !m.group(1).startsWith("http") =>
          makeJavadocLink(lineNo, m.start(1), m.end(1), m.group(1), fileUri)
      }
      .toSeq

    val seeTagLinks = javadocSeeTagRegex
      .findAllMatchIn(line)
      .collect {
        case m if !m.group(1).startsWith("http") =>
          makeJavadocLink(lineNo, m.start(1), m.end(1), m.group(1), fileUri)
      }
      .toSeq

    linkTagLinks ++ seeTagLinks
  }

  private def makeUrlLink(
      lineNo: Int,
      m: scala.util.matching.Regex.Match,
  ): DocumentLink = {
    val url = trimTrailingPunct(m.matched)
    val link = new DocumentLink()
    link.setTarget(url)
    link.setTooltip(
      scala.util.Try(URLDecoder.decode(url, "UTF-8")).getOrElse(url)
    )
    link.setRange(
      new Range(
        new Position(lineNo, m.start),
        new Position(lineNo, m.start + url.length),
      )
    )
    link
  }

  /**
   * Creates a DocumentLink for a Javadoc class/member reference. The
   * {@code target} is intentionally left empty — the actual file URI is
   * resolved lazily in the {@code documentLink/resolve} phase (issue #8303).
   * The {@code data} field carries the information the resolve handler needs:
   * the symbol reference and the URI of the document that contains it.
   */
  private def makeJavadocLink(
      lineNo: Int,
      start: Int,
      end: Int,
      ref: String,
      fileUri: String,
  ): DocumentLink = {
    val data = new JsonObject()
    data.addProperty("symbol", ref)
    data.addProperty("uri", fileUri)
    val link = new DocumentLink()
    link.setTooltip(ref)
    link.setData(data)
    link.setRange(
      new Range(new Position(lineNo, start), new Position(lineNo, end))
    )
    link
  }

  /**
   * Strips trailing punctuation characters that are unlikely to be part of
   * an intentional URL. Handles balanced parentheses/brackets: a closing
   * bracket is only stripped when there is no matching opener in the URL.
   */
  private def trimTrailingPunct(url: String): String = {
    var end = url.length
    var continue = true
    while (continue && end > 0) {
      url.charAt(end - 1) match {
        case '.' | ',' | ';' | ':' | '!' | '?' | '>' | '\'' | '"' =>
          end -= 1
        case ')' if !url.substring(0, end - 1).contains('(') => end -= 1
        case ']' if !url.substring(0, end - 1).contains('[') => end -= 1
        case '}' if !url.substring(0, end - 1).contains('{') => end -= 1
        case _ => continue = false
      }
    }
    url.substring(0, end)
  }
}
