package tests

import scala.collection.mutable.ListBuffer

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.InlayHints

import com.google.gson.JsonElement
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

object TestInlayHints {

  // For not local symbols - semanticdb symbol
  //  val x = 123
  //      |
  //      v
  //  val x<<: Int/*scala/predef/Int#*/>> = 123
  // For local symbols - definition position in source
  // type T = Int
  // val x: T = ???
  // val y = x
  //     |
  //     v
  // val y<<: T/*(0:5,0:5)*/>> = x
  def decorationString(inlayHint: InlayHint, withTooltip: Boolean): String = {
    val buffer = ListBuffer.empty[String]

    val labels = inlayHint.getLabel().asScala match {
      case Left(label) => List(label)
      case Right(labelParts) => labelParts.asScala.map(_.getValue()).toList
    }

    val tooltip = Option(inlayHint.getTooltip()).map(t =>
      t.asScala match {
        case Left(tooltip) => tooltip
        case Right(markdown) => markdown.getValue()
      }
    )
    val data = inlayHint.getData()

    buffer += "/*"
    if (data != null) {
      val dataDecoded =
        InlayHints.fromData(data.asInstanceOf[JsonElement])._2
      labels.zip(dataDecoded).foreach { case (label, data) =>
        buffer += label
        buffer ++= readData(data)
      }
    } else {
      buffer += labels.mkString
    }
    if (withTooltip)
      tooltip.foreach { tooltip =>
        buffer += "| "
        buffer += tooltip.replace("\n", "\\n")
        buffer += " |"
      }
    buffer += "*/"
    buffer.toList.mkString
  }

  private def readData(data: Either[String, l.Position]): List[String] = {
    data match {
      case Left("") => Nil
      case Left(data) => List("<<", data, ">>")
      case Right(data) =>
        val str = s"(${data.getLine()}:${data.getCharacter()})"
        List("<<", str, ">>")
    }
  }

  def applyInlayHints(
      text: String,
      inlayHints: List[InlayHint],
      withTooltip: Boolean
  ): String = {
    val textEdits = inlayHints.map { hint =>
      val newText = decorationString(hint, withTooltip)
      val range = new l.Range(hint.getPosition(), hint.getPosition())
      new TextEdit(
        range,
        newText
      )
    }
    TextEdits.applyEdits(text, textEdits)
  }

  def removeInlayHints(text: String): String =
    text.replaceAll(raw"\/\*(.*?)\*\/", "")

}
