package tests

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.reflect.classTag
import scala.util.Try

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.mtags.CommonMtagsEnrichments._

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
object TestInlayHints {
//
//  val x = 123
//      |
//      |
//      v
//  val x<<: Int/*scala/predef/Int#*/>> = 123
  def decorationString(inlayHint: InlayHint): String = {
    val buffer = ListBuffer.empty[String]

    val labels = inlayHint.getLabel().asScala match {
      case Left(label) => List(label)
      case Right(labelParts) => labelParts.asScala.map(_.getValue()).toList
    }
    // val parser = new JsonParser.Of[List[String]]
    val data =
      inlayHint.getData().asInstanceOf[java.util.List[String]].asScala.toList
    buffer += "/*"
    labels.zip(data).foreach { case (label, data) =>
      if (data.nonEmpty) {
        buffer ++= List(
          label,
          "<<",
          data,
          ">>",
        )
      } else {
        buffer ++= List(
          label
        )
      }
    }
    buffer += "*/"
    buffer.toList.mkString
  }

  def applyInlayHints(text: String, inlayHints: List[InlayHint]): String = {
    val textEdits = inlayHints.map { hint =>
      val newText = decorationString(hint)
      val range = new l.Range(hint.getPosition(), hint.getPosition())
      new TextEdit(
        range,
        newText,
      )
    }
    TextEdits.applyEdits(text, textEdits)
  }

  object JsonParser {
    private val gson = new Gson()
    implicit class XtensionSerializedAsJson(json: JsonElement) {
      def as[A: ClassTag]: Try[A] = {
        val targetType = classTag[A].runtimeClass.asInstanceOf[Class[A]]
        Try(JsonParser.gson.fromJson(json, targetType))
      }
    }

    class Of[A: ClassTag] {
      object Jsonized {
        def unapply(json: JsonElement): Option[A] = {
          json.as[A].toOption
        }
      }
    }
  }

}
