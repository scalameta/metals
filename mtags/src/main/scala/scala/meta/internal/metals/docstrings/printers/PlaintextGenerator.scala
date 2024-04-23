package scala.meta.internal.docstrings.printers

import scala.meta.internal.docstrings._

object PlaintextGenerator extends ScalaDocPrinter {

  def blockToText(block: Block, listLevel: Int): String =
    block match {
      case Title(text, _) =>
        s"""==${inlineToText(text)}=="""
      case UnorderedList(blocks) =>
        listBlocksIndent(blocks, ListType.Bullet('-'), listLevel)
      case OrderedList(blocks, _) =>
        listBlocksIndent(blocks, ListType.Ordered, listLevel)
      case Paragraph(text) => s"${inlineToText(text)}\n"
      case Code(data) => s"{{{\n$data\n}}}\n"
      case _ => ""
    }

  def inlineToText(i: Inline): String =
    i match {
      case Chain(items) => items.map(inlineToText).mkString
      case Summary(text) => inlineToText(text)
      case Monospace(text) => inlineToText(text)
      case Italic(text) => inlineToText(text)
      case Bold(text) => inlineToText(text)
      case Link(_, title) => s"[[${inlineToText(title)}]]"
      case Text(text) => text
      case HtmlTag(data) => data
      case _ => ""
    }

  protected def wrapParam(param: String): String = param
  protected def constructor: String = "@constructor"
  protected def deprecated: String = "@deprecated"
  protected def examples: String = "@example"
  override protected def note: String = "@note"
  override protected def typeParam: String = "@tparam"
  override protected def param: String = "@param"
  protected def returns: String = "@returns"
  override protected def throws: String = "@throws"
  override protected def see: String = "@see"
}
