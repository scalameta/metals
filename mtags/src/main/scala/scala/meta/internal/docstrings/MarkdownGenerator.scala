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
    code.tokenize.get
      .collect {
        case c: Token.Comment if c.syntax.startsWith("/**") =>
          val commentAST =
            ScaladocParser.parseAtSymbol(HtmlConverter.convert(c.syntax))
          Seq(
            Option(commentAST.body)
              .map(body => blocksToMarkdown(body.blocks))
              .mkString,
            if (commentAST.authors.nonEmpty)
              "\n**Authors**\n" + commentAST.authors
                .map(body => "- " ++ blocksToMarkdown(body.blocks))
                .mkString("\n")
            else "",
            if (commentAST.version.nonEmpty)
              "\n" + commentAST.version
                .map(body => "**Version:** " ++ blocksToMarkdown(body.blocks))
                .mkString("\n")
            else "",
            if (commentAST.constructor.nonEmpty)
              "\n" + commentAST.constructor
                .map(
                  body => "**Constructor:** " + blocksToMarkdown(body.blocks)
                )
                .mkString("\n")
            else "",
            if (commentAST.deprecated.nonEmpty)
              "\n" + commentAST.deprecated
                .map(
                  body => "**Deprecated:** " ++ blocksToMarkdown(body.blocks)
                )
                .mkString("\n")
            else "",
            if (commentAST.example.nonEmpty)
              "\n**Examples**\n" + commentAST.example
                .map(body => blocksToMarkdown(body.blocks))
                .mkString("\n", "\n", "")
            else "",
            if (commentAST.note.nonEmpty)
              "\n**Notes**\n" +
                commentAST.note
                  .map(body => "- " + blocksToMarkdown(body.blocks))
                  .mkString("\n")
            else "",
            if (commentAST.typeParams.nonEmpty)
              "\n**Type Parameters**\n" +
                commentAST.typeParams
                  .map(
                    tuple =>
                      s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
                  )
                  .mkString
            else
              "",
            if (commentAST.valueParams.nonEmpty)
              "\n**Parameters**\n" + commentAST.valueParams
                .map(
                  tuple =>
                    s"- `${tuple._1}`: " + blocksToMarkdown(tuple._2.blocks)
                )
                .mkString
            else
              "",
            if (commentAST.result.nonEmpty)
              "\n" + commentAST.result
                .map(body => "**Returns:** " ++ blocksToMarkdown(body.blocks))
                .mkString("\n")
            else "",
            if (commentAST.throws.nonEmpty)
              "\n**Throws**\n" + commentAST.throws
                .map(
                  tuple =>
                    s"- `${tuple._1}`: " + tuple._2.summary
                      .map(inlineToMarkdown)
                      .getOrElse("") + blocksToMarkdown(tuple._2.blocks)
                )
                .mkString("", "\n", "\n")
            else "",
            if (commentAST.see.nonEmpty)
              "\n**See**\n" + commentAST.see
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
