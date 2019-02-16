package scala.meta.internal.docstrings

import scala.meta._

/**
 * Generates markdown from the docstring
 * The markdown can be returned to the IDE and rendered to the user
 */
object MarkdownGenerator {

  /**
   * Generates markdown from code
   *
   * @param code the input code
   * @return a sequence of markdown strings - one per docstring comment in the code
   */
  def toMarkdown(code: String): Seq[String] =
    code.tokenize.get.collect {
      case c: Token.Comment if c.syntax.startsWith("/**") =>
        fromDocstring(c.syntax, Map.empty)
    }

  def fromDocstring(
      docstring: String,
      defines: collection.Map[String, String]
  ): String = {
    toMarkdown(ScaladocParser.parseComment(docstring, defines))
  }

  def toMarkdown(b: Body): String = {
    Option(b)
      .map(body => blocksToMarkdown(body.blocks))
      .mkString
  }
  def toMarkdown(c: Comment): String = {
    Seq(
      toMarkdown(c.body),
      if (c.authors.nonEmpty)
        "\n**Authors**\n" + c.authors
          .map(body => "- " ++ blocksToMarkdown(body.blocks))
          .mkString("\n")
      else "",
      if (c.version.nonEmpty)
        "\n" + c.version
          .map(body => "**Version:** " ++ blocksToMarkdown(body.blocks))
          .mkString("\n")
      else "",
      if (c.constructor.nonEmpty)
        "\n" + c.constructor
          .map(
            body => "**Constructor:** " + blocksToMarkdown(body.blocks)
          )
          .mkString("\n")
      else "",
      if (c.deprecated.nonEmpty)
        "\n" + c.deprecated
          .map(
            body => "**Deprecated:** " ++ blocksToMarkdown(body.blocks)
          )
          .mkString("\n")
      else "",
      if (c.example.nonEmpty)
        "\n**Examples**\n" + c.example
          .map(body => blocksToMarkdown(body.blocks))
          .mkString("\n", "\n", "")
      else "",
      if (c.note.nonEmpty)
        "\n**Notes**\n" +
          c.note
            .map(body => "- " + blocksToMarkdown(body.blocks))
            .mkString("\n")
      else "",
      if (c.typeParams.nonEmpty)
        "\n**Type Parameters**\n" +
          c.typeParams
            .map(
              tuple => s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
            )
            .mkString
      else
        "",
      if (c.valueParams.nonEmpty)
        "\n**Parameters**\n" + c.valueParams
          .map(
            tuple => s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
          )
          .mkString
      else
        "",
      if (c.result.nonEmpty)
        "\n" + c.result
          .map(body => "**Returns:** " ++ blocksToMarkdown(body.blocks))
          .mkString("\n")
      else "",
      if (c.throws.nonEmpty)
        "\n**Throws**\n" + c.throws
          .map(
            tuple =>
              s"- `${tuple._1}`: " + tuple._2.summary
                .map(inlineToMarkdown)
                .getOrElse("") + blocksToMarkdown(tuple._2.blocks)
          )
          .mkString("", "\n", "\n")
      else "",
      if (c.see.nonEmpty)
        "\n**See**\n" + c.see
          .map(
            body => "- [" ++ blocksToMarkdown(body.blocks).trim + "]()"
          )
          .mkString("", "\n", "\n")
      else ""
    ).reduce(_ + _).trim
  }

  private def blocksToMarkdown(blocks: Seq[Block], listLevel: Int = 0): String =
    blocks.map(block => blockToMarkdown(block)).mkString("\n")

  private def blockToMarkdown(block: Block, listLevel: Int = 0): String =
    block match {
      case Title(text, level) =>
        s"""${"#" * level} ${inlineToMarkdown(text)}"""
      case UnorderedList(blocks) =>
        blocks.map {
          case o: OrderedList =>
            blockToMarkdown(o, listLevel + 1)
          case u: UnorderedList =>
            blockToMarkdown(u, listLevel + 1)
          case x =>
            s"""${"\t" * listLevel}- ${blockToMarkdown(x, listLevel + 1)}"""
        }.mkString
      case OrderedList(blocks, _) =>
        blocks.map {
          case o @ OrderedList(items, style) =>
            blockToMarkdown(o, listLevel + 1)
          case u @ UnorderedList(items) =>
            blockToMarkdown(u, listLevel + 1)
          case x =>
            s"""${"\t" * listLevel}* ${blockToMarkdown(x, listLevel + 1)}"""
        }.mkString
      case Paragraph(text) =>
        s"${inlineToMarkdown(text)}\n"
      case Code(data) =>
        s"```\n$data\n```"
      case HorizontalRule() =>
        "---"
      case _ =>
        ""
    }

  private def inlineToMarkdown(i: Inline): String = {
    i match {
      case Chain(items) =>
        items.map(inlineToMarkdown).mkString
      case Summary(text) =>
        inlineToMarkdown(text)
      case Monospace(text) =>
        s"`${inlineToMarkdown(text)}`"
      case Text(text) =>
        text
      case HtmlTag(data) =>
        data
      case Italic(text) =>
        s"*${inlineToMarkdown(text)}*"
      case Bold(text) =>
        s"**${inlineToMarkdown(text)}**"
      case Link(target, title) =>
        s"[$target](${inlineToMarkdown(title)})"
      case _ =>
        ""
    }
  }
}
