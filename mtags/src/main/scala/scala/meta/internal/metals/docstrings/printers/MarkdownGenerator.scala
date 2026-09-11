package scala.meta.internal.docstrings.printers

import scala.collection.Seq

import scala.meta._
import scala.meta.dialects.Scala213
import scala.meta.internal.docstrings._
import scala.meta.internal.mtags.MtagsEnrichments._

/**
 * Generates markdown from the docstring
 * The markdown can be returned to the IDE and rendered to the user
 */
object MarkdownGenerator extends ScalaDocPrinter {

  /**
   * Generates markdown from code
   *
   * @param code the input code
   * @return a sequence of markdown strings - one per docstring comment in the code
   */
  def toText(code: String): Seq[String] =
    code.safeTokenize(Scala213).get.collect {
      case c: Token.Comment if c.syntax.startsWith("/**") =>
        fromDocstring(c.syntax, Map.empty)
    }

  def fromDocstring(
      docstring: String,
      defines: collection.Map[String, String]
  ): String = {
    super.toText(ScaladocParser.parseComment(docstring, defines), docstring)
  }

  protected def blockToText(block: Block, listLevel: Int): String =
    block match {
      case Title(text, level) =>
        s"""${"#" * level} ${inlineToText(text)}"""
      case UnorderedList(blocks) =>
        listBlocksIndent(blocks, ListType.Bullet('-'), listLevel)
      case OrderedList(blocks, _) =>
        listBlocksIndent(blocks, ListType.Bullet('*'), listLevel)
      case Paragraph(text) =>
        s"${inlineToText(text)}\n"
      case Code(data) =>
        s"```\n$data\n```"
      case HorizontalRule() =>
        "---"
      case _ =>
        ""
    }

  protected def inlineToText(i: Inline): String = {
    i match {
      case Chain(items) =>
        items.map(inlineToText).mkString
      case Summary(text) =>
        inlineToText(text)
      case Monospace(text) =>
        s"`${inlineToText(text)}`"
      case Text(text) =>
        text
      case HtmlTag(data) =>
        data
      case Italic(text) =>
        s"*${inlineToText(text)}*"
      case Bold(text) =>
        s"**${inlineToText(text)}**"
      case Link(target, title) =>
        val label = inlineToText(title)
        if (isUrl(target)) s"[$label]($target)"
        // Entity (wiki) links point at a symbol, not a URL; mark them so the
        // server can resolve them. Escape the label so brackets in a title like
        // `Option[A]` can't break the marker (scalameta/metals#3383).
        else
          s"[${escapeLinkLabel(label)}](${MetalsSymbolLink.scheme}${MetalsSymbolLink
              .encode(target)})"
      case _ =>
        ""
    }
  }

  /**
   * Whether a link target is an external URL rather than a symbol reference.
   * Only a leading `scheme:` marks a URL; a bare `/` can't, since entity paths
   * may contain it (e.g. `BigDecimal./`) (scalameta/metals#3383).
   */
  private def isUrl(target: String): Boolean =
    target.indexOf(':') match {
      case -1 => false
      case colon => isScheme(target.substring(0, colon))
    }

  /**
   * Whether `s` is an RFC 3986 scheme: `ALPHA *( ALPHA / DIGIT / "+" / "-" /
   * "." )`. Dots are allowed (a custom scheme such as `com.example`) but the
   * scheme must begin and end with a letter or digit and contain no doubled dot.
   * No real scheme ends in `+`/`-`/`.`, whereas a dotted symbol path leading into
   * an operator member always does — so requiring an alphanumeric last character
   * keeps `List.+:` & friends symbols. The grammar is inherently case-insensitive,
   * so no locale-sensitive lower-casing is needed.
   */
  private def isScheme(s: String): Boolean =
    s.nonEmpty && s.charAt(0).isLetter &&
      s.charAt(s.length - 1).isLetterOrDigit && {
        var i = 0
        var prevDot = false
        var ok = true
        while (i < s.length && ok) {
          val c = s.charAt(i)
          if (c == '.') { ok = !prevDot; prevDot = true }
          else {
            ok = c.isLetterOrDigit || c == '+' || c == '-'
            prevDot = false
          }
          i += 1
        }
        ok
      }

  /** Backslash-escapes the markdown link-label delimiters `\`, `[` and `]`. */
  private def escapeLinkLabel(label: String): String = {
    val sb = new StringBuilder(label.length)
    label.foreach { c =>
      if (c == '\\' || c == '[' || c == ']') sb.append('\\')
      sb.append(c)
    }
    sb.toString
  }

  protected def wrapParam(param: String): String = s"`$param`"
  protected def constructor: String = "**Constructor:**"
  protected def deprecated: String = "**Deprecated:**"
  protected def examples: String = "**Examples**\n"
  override protected def notesTitle: String = "**Notes**\n"
  override protected def typeParamsTitle: String = "**Type Parameters**\n"
  override protected def parametersTitle: String = "**Parameters**\n"
  protected def returns: String = "**Returns:**"
  override protected def throwsTitle: String = "**Throws**\n"
  override protected def seeTitle: String = "**See**\n"

}
