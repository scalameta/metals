package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}

case class Command(
    id: String,
    title: String,
    description: String,
    arguments: String = "`null`"
) {
  def unapply(string: String): Boolean = string == id

  def toLSP(arguments: List[AnyRef]): l.Command =
    new l.Command(title, id, arguments.asJava)
}

object Argument {
  lazy val rangeParser = new JsonParser.Of[l.Range]

  def getAsString(obj: AnyRef): Option[String] = {
    obj match {
      case p: JsonPrimitive if p.isString => Option(p.getAsString())
      case _ => None
    }
  }

  def getAsInt(obj: AnyRef): Option[Int] = {
    obj match {
      case p: JsonPrimitive if p.isNumber => Option(p.getAsInt())
      case _ => None
    }
  }

  def getAsBoolean(obj: AnyRef): Option[Boolean] = {
    obj match {
      case p: JsonPrimitive if p.isBoolean => Option(p.getAsBoolean())
      case _ => None
    }
  }

  def getAsRange(obj: AnyRef): Option[l.Range] = {
    obj match {
      case rangeParser.Jsonized(range) =>
        Option(range)
      case _ => None
    }
  }

}
