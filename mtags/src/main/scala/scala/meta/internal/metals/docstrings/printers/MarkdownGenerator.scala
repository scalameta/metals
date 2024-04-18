package scala.meta.internal.docstrings.printers

import scala.collection.Seq

import scala.meta._
import scala.meta.dialects.Scala213
import scala.meta.internal.docstrings._

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
    Scala213(code).tokenize.get.collect {
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
        s"[${inlineToText(title)}]($target)"
      case _ =>
        ""
    }
  }

  protected def wrapParam(param: String): String = s"`$param`"
  protected def constructor: String = "**Constructor**"
  protected def deprecated: String = "**Deprecated**"
  protected def examples: String = "**Examples**\n"
  override protected def notesTitle: String = "**Notes**\n"
  override protected def typeParamsTitle: String = "**Type Parameters**\n"
  override protected def parametersTitle: String = "**Parameters**\n"
  protected def returns: String = "**Returns:**"
  override protected def throwsTitle: String = "**Throws**\n"
  override protected def seeTitle: String = "**See**\n"

}
