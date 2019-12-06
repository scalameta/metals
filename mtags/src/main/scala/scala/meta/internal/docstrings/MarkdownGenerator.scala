package scala.meta.internal.docstrings

import scala.meta._
import scala.collection.Seq

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
      if (c.constructor.nonEmpty)
        "\n" + c.constructor
          .map(body => "**Constructor:** " + blocksToMarkdown(body.blocks))
          .mkString("\n")
      else "",
      if (c.deprecated.nonEmpty)
        "\n" + c.deprecated
          .map(body => "**Deprecated:** " ++ blocksToMarkdown(body.blocks))
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
            .map(tuple =>
              s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
            )
            .mkString
      else
        "",
      if (c.valueParams.nonEmpty)
        "\n**Parameters**\n" + c.valueParams
          .map(tuple => s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
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
          .map(tuple =>
            s"- `${tuple._1}`: " + tuple._2.summary
              .map(inlineToMarkdown)
              .getOrElse("") + blocksToMarkdown(tuple._2.blocks)
          )
          .mkString("", "\n", "\n")
      else "",
      if (c.see.nonEmpty)
        "\n**See**\n" + c.see
          .map { body =>
            s"- ${blocksToMarkdown(body.blocks).trim}"
          }
          .mkString("", "\n", "\n")
      else ""
    ).reduce(_ + _).trim
  }

  private def blocksToMarkdown(blocks: Seq[Block], listLevel: Int = 0): String =
    blocks.map(block => blockToMarkdown(block, listLevel)).mkString("\n")

  private def listBlockIndent(b: Block, bullet: Char, listLevel: Int): String =
    b match {
      case _: OrderedList | _: UnorderedList =>
        ""
      case _ =>
        s"""${"\t" * listLevel}${bullet} """
    }

  private def listBlocksIndent(
      blocks: Seq[Block],
      bullet: Char,
      listLevel: Int
  ): String =
    blocks
      .map((b: Block) =>
        s"${this.listBlockIndent(b, bullet, listLevel)}${this
          .blockToMarkdown(b, listLevel + 1)}"
      )
      .mkString

  private def blockToMarkdown(block: Block, listLevel: Int): String =
    block match {
      case Title(text, level) =>
        s"""${"#" * level} ${inlineToMarkdown(text)}"""
      case UnorderedList(blocks) =>
        this.listBlocksIndent(blocks, '-', listLevel)
      case OrderedList(blocks, _) =>
        this.listBlocksIndent(blocks, '*', listLevel)
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
        s"[${inlineToMarkdown(title)}]($target)"
      case _ =>
        ""
    }
  }
}
