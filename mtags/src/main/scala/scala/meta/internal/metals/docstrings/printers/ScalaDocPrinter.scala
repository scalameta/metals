package scala.meta.internal.docstrings.printers

import scala.collection.Seq
import scala.util.matching.Regex

import scala.meta.internal.docstrings._

abstract class ScalaDocPrinter {
  def toText(b: Body): String = {
    Option(b)
      .map(body => blocksToText(body.blocks))
      .mkString
  }

  def toText(c: Comment, docstring: String): String = {

    def sortInSection(
        section: String,
        items: Seq[(String, Body)]
    ): Seq[(String, Body)] = {
      items.sortBy { case (key, _) =>
        val reg = new Regex(s"@$section\\s+$key")
        reg.findFirstMatchIn(docstring).map(_.start).getOrElse(Int.MaxValue)
      }
    }

    Seq(
      toText(c.body),
      if (c.constructor.nonEmpty)
        "\n" + c.constructor
          .map(body => constructor + " " + blocksToText(body.blocks))
          .mkString("\n")
      else "",
      if (c.deprecated.nonEmpty)
        "\n" + c.deprecated
          .map(body => deprecated ++ " " ++ blocksToText(body.blocks))
          .mkString("\n")
      else "",
      if (c.example.nonEmpty)
        s"\n$examples" + c.example
          .map(body => blocksToText(body.blocks))
          .mkString("\n", "\n", "")
      else "",
      if (c.note.nonEmpty)
        s"\n$notesTitle" +
          c.note
            .map(body => s"$note " + blocksToText(body.blocks))
            .mkString("\n")
      else "",
      if (c.typeParams.nonEmpty)
        s"\n$typeParamsTitle" +
          sortInSection("tparam", c.typeParams.toSeq)
            .map(tuple =>
              s"$typeParam ${wrapParam(tuple._1)}: " + blocksToText(
                tuple._2.blocks
              )
            )
            .mkString
      else
        "",
      if (c.valueParams.nonEmpty)
        s"\n$parametersTitle" + sortInSection("param", c.valueParams.toSeq)
          .map(tuple =>
            s"$param ${wrapParam(tuple._1)}: " + blocksToText(tuple._2.blocks)
          )
          .mkString
      else
        "",
      if (c.result.nonEmpty)
        "\n" + c.result
          .map(body => returns ++ " " ++ blocksToText(body.blocks))
          .mkString("\n")
      else "",
      if (c.throws.nonEmpty)
        s"\n$throwsTitle" + sortInSection("throws", c.throws.toSeq)
          .map(tuple =>
            s"$throws ${wrapParam(tuple._1)}: " + tuple._2.summary
              .map(inlineToText)
              .getOrElse("")
          )
          .mkString("", "\n", "\n")
      else "",
      if (c.see.nonEmpty)
        s"\n$seeTitle" + c.see
          .map { body => s"$see ${blocksToText(body.blocks).trim}" }
          .mkString("", "\n", "\n")
      else ""
    ).reduce(_ + _).trim
  }

  protected def blocksToText(blocks: Seq[Block], listLevel: Int = 0): String =
    blocks.map(block => blockToText(block, listLevel)).mkString("\n")

  protected def listBlocksIndent(
      blocks: Seq[Block],
      listType: ListType,
      listLevel: Int
  ): String = {
    var index = 1

    (for (block <- blocks) yield {
      val ident =
        block match {
          case _: OrderedList | _: UnorderedList => ""
          case _ =>
            val bullet = listType.getBullet(index)
            index += 1
            s"""${"\t" * listLevel}${bullet} """
        }
      s"${ident}${blockToText(block, listLevel + 1)}"
    }).mkString
  }

  protected def blockToText(block: Block, listLevel: Int): String
  protected def inlineToText(i: Inline): String
  protected def wrapParam(param: String): String
  protected def constructor: String
  protected def deprecated: String
  protected def examples: String
  protected def notesTitle: String = ""
  protected def note: String = "-"
  protected def typeParamsTitle: String = ""
  protected def typeParam: String = "-"
  protected def parametersTitle: String = ""
  protected def param: String = "-"
  protected def returns: String
  protected def throwsTitle: String = ""
  protected def throws: String = "-"
  protected def seeTitle: String = ""
  protected def see: String = "-"

}

trait ListType {
  def getBullet(i: Int): String
}
object ListType {
  case object Ordered extends ListType {
    def getBullet(i: Int): String = i.toString()
  }

  case class Bullet(c: Char) extends ListType {
    def getBullet(i: Int): String = c.toString()
  }
}
