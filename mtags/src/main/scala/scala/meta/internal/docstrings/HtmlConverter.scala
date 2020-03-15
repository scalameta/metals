package scala.meta.internal.docstrings

import org.jsoup.Jsoup
import org.jsoup.nodes.Entities.EscapeMode
import org.jsoup.nodes.{Element, Node, TextNode}
import org.jsoup.safety.{Cleaner, Whitelist}

import scala.annotation.tailrec
import scala.meta.internal.jdk.CollectionConverters._
import scala.util.matching.Regex

// TODO - Add conversion of tables

/**
 * Converts HTML in docstrings to Markdown readable by Scaladoc parser
 */
object HtmlConverter {

  // Borrowed from ScaladocParser
  private val trailingWhitespaceRegex = """\s+$""".r
  private val cleanCommentLine = new Regex("""(?:\s*\*\s?)?(.*)""")
  private def cleanCode(line: String): String =
    line
      .split("\n")
      .map(_.trim)
      .map { line =>
        if (line.startsWith("/**") || line.endsWith("*/")) {
          line
        } else {
          // Remove trailing whitespaces and remove leading * inside comment
          trailingWhitespaceRegex.replaceAllIn(line, "") match {
            case cleanCommentLine(ctl) => ctl
            case tl => tl
          }
        }
      }
      .mkString("\n")

  /**
   * Convert HTML tags to Markdown that can be processed by the Scaladoc parser
   *
   * @param code docstring comment with HTML tags
   * @return  docstring comment without HTML tags
   */
  def convert(code: String): String = {
    // Removes leading * from rows inside of the comment
    // This is done as they otherwise get included
    // as text by the HTML parser
    val cleanedCode = cleanCode(code)

    // Parse html in docstring
    val whitelist = Whitelist.relaxed
    val cleaner = new Cleaner(whitelist)
    val doc = cleaner.clean(Jsoup.parse(cleanedCode))
    doc.outputSettings().escapeMode(EscapeMode.xhtml)

    // Process HTML AST and convert to Markdown
    // then add leading * back to rows inside of comment
    processHtmlNode(doc.body)
      .split("\n")
      .map { line =>
        if (line.trim.startsWith("/**") || line.trim.endsWith("*/"))
          line
        else
          s"* $line"
      }
      .mkString("\n")
  }

  private def processHtmlNode(node: Node): String = {
    node match {
      case t: TextNode => t.getWholeText
      case e: Element =>
        processHtmlElement(e)
      case _ =>
        println(s"unknown element - $node")
        ""
    }
  }

  private def processHtmlElement(element: Element): String = {
    lazy val childText = element
      .childNodes()
      .asScala
      .map { node => processHtmlNode(node) }
      .mkString
    element.tagName().toLowerCase match {
      case "body" => childText
      case "h1" => s"# $childText"
      case "h2" => s"## $childText"
      case "h3" => s"### $childText"
      case "h4" | "h5" | "h6" => s"#### $childText"
      case "p" | "div" => s"$childText\n\n"
      case "strong" | "b" => s"**$childText**"
      case "em" | "i" => s"*$childText*"
      case "code" => s"`$childText`"
      case "a" => s"[$childText](${element.attr("href")})"
      case "pre" => s"{{{$childText}}}"
      case "ul" | "ol" => childText
      case "li" =>
        val indentLevel: Int = listLevel(element)
        val listSymbol =
          if (element.parent().tagName.toLowerCase == "ol") "*" else "-"
        element
          .childNodes()
          .asScala
          .collect {
            case list
                if list
                  .nodeName()
                  .toLowerCase == "ol" || list.nodeName().toLowerCase == "ul" =>
              processHtmlNode(list)
            case node =>
              val nodeText = processHtmlNode(node).trim
              if (nodeText.nonEmpty)
                s"""${"\t" * indentLevel}$listSymbol $nodeText"""
              else
                nodeText
          }
          .mkString
      case _ =>
        ""
    }
  }

  private def listLevel(element: Element): Int = {
    @tailrec
    def findListLevel(current: Element, level: Int): Int = {
      if (current.tagName.toLowerCase == "body")
        level
      else if (current.parent.tagName().toLowerCase == "li")
        findListLevel(current.parent(), level + 1)
      else
        findListLevel(current.parent(), level)
    }
    findListLevel(element, 0)
  }
}
