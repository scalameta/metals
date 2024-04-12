package scala.meta.internal.docstrings

import scala.collection.Seq
import scala.util.matching.Regex

object PlaintextGenerator {
  def toPlaintext(b: Body): String =
    Option(b).map(body => printBlocks(body.blocks)).mkString("\n")

  def toPlaintext(c: Comment, docstring: String): String = {

    def sortInSection(
        section: String,
        items: Seq[(String, Body)]
    ): Seq[(String, Body)] = {
      items.sortBy { case (key, _) =>
        val reg = new Regex(s"@$section\\s+$key")
        reg.findFirstMatchIn(docstring).map(_.start).getOrElse(Int.MaxValue)
      }
    }

    def printSection(name: String, optBody: Seq[Body]) =
      if (optBody.nonEmpty)
        optBody
          .map(body => s"@$name ${printBlocks(body.blocks)}")
          .mkString("\n", "\n", "")
      else ""

    def printSortedSection(name: String, section: Seq[(String, Body)]) =
      if (section.nonEmpty)
        sortInSection(name, section)
          .map(tuple => s"@$name ${tuple._1}: " + printBlocks(tuple._2.blocks))
          .mkString("\n", "\n", "")
      else ""

    Seq(
      toPlaintext(c.body),
      printSection("constructor", c.constructor.toSeq),
      printSection("deprecated", c.deprecated.toSeq),
      printSection("example", c.example),
      printSection("notes", c.note),
      printSortedSection("tparam", c.typeParams.toSeq),
      printSortedSection("param", c.valueParams.toSeq),
      printSection("returns", c.result.toSeq),
      printSortedSection("throws", c.throws.toSeq),
      printSection("see", c.see)
    ).reduce(_ + _).trim
  }

  private def printBlocks(blocks: Seq[Block]): String =
    blocks.map(block => printBlock(block, listLevel = 0)).mkString("\n")

  private def listBlocksIndent(
      blocks: Seq[Block],
      isOrdered: Boolean,
      listLevel: Int
  ) = {
    var index = 1

    for (block <- blocks) yield {
      val ident =
        block match {
          case _: OrderedList | _: UnorderedList => ""
          case _ =>
            val bullet = if (isOrdered) index.toString() else "-"
            index += 1
            s"""${"\t" * listLevel}${bullet} """
        }
      s"${ident}${printBlock(block, listLevel + 1)}"
    }
  }

  private def printBlock(block: Block, listLevel: Int): String =
    block match {
      case Title(text, _) =>
        s"""==${printInline(text)}=="""
      case UnorderedList(blocks) =>
        this.listBlocksIndent(blocks, isOrdered = false, listLevel).mkString
      case OrderedList(blocks, _) =>
        this.listBlocksIndent(blocks, isOrdered = true, listLevel).mkString
      case Paragraph(text) => s"${printInline(text)}"
      case Code(data) => s"{{{\n$data\n}}}"
      case _ => ""
    }

  private def printInline(i: Inline): String =
    i match {
      case Chain(items) => items.map(printInline).mkString
      case Summary(text) => printInline(text)
      case Monospace(text) => printInline(text)
      case Italic(text) => printInline(text)
      case Bold(text) => printInline(text)
      case Link(_, title) => s"[[${printInline(title)}]]"
      case Text(text) => text
      case HtmlTag(data) => data
      case _ => ""
    }
}
