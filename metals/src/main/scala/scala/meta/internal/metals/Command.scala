package scala.meta.internal.metals

import scala.reflect.ClassTag
import scala.util.matching.Regex

import scala.meta.internal.metals.MetalsEnrichments._

import com.google.gson.JsonPrimitive
import org.eclipse.{lsp4j => l}

trait BaseCommand {
  def id: String
  def title: String
  def description: String
  def arguments: String

  def toLSP(arguments: List[AnyRef]): l.Command =
    new l.Command(title, id, arguments.asJava)

  protected def isApplicableCommand(params: l.ExecuteCommandParams): Boolean = {
    val command = Option(params.getCommand).getOrElse("")
    command.stripPrefix("metals.") == id
  }
}

case class Command(
    id: String,
    title: String,
    description: String,
    arguments: String = "`null`"
) extends BaseCommand {
  def unapply(params: l.ExecuteCommandParams): Boolean = {
    isApplicableCommand(params)
  }

}

case class OpenBrowserCommand(
    url: String,
    title: String,
    description: String
) extends BaseCommand {
  def id: String = s"browser-open-url:$url"
  def arguments: String = "`null`"

}

object OpenBrowserCommand {
  private val OpenBrowser: Regex = "browser-open-url:(.*)".r
  def unapply(params: l.ExecuteCommandParams): Option[String] = {
    val command = Option(params.getCommand).getOrElse("")
    command match {
      case OpenBrowser(url) => Some(url)
      case _ => None
    }
  }
}

case class ParametrizedCommand1[T1: ClassTag](
    id: String,
    title: String,
    description: String,
    arguments: String
) extends BaseCommand {

  private val parser = new JsonParser.Of[T1]

  def unapply(params: l.ExecuteCommandParams): Option[T1] = {
    val args = Option(params.getArguments()).toList.flatMap(_.asScala)
    if (args.size != 1 || !isApplicableCommand(params)) None
    else {
      args(0) match {
        case parser.Jsonized(t1) =>
          Some(t1)
        case _ => None
      }
    }
  }

}

case class ParametrizedCommand3[T1: ClassTag, T2: ClassTag, T3: ClassTag](
    id: String,
    title: String,
    description: String,
    arguments: String
) extends BaseCommand {

  private val parser1 = new JsonParser.Of[T1]
  private val parser2 = new JsonParser.Of[T2]
  private val parser3 = new JsonParser.Of[T3]

  def unapply(params: l.ExecuteCommandParams): Option[(T1, T2, T3)] = {
    val args = Option(params.getArguments()).toList.flatMap(_.asScala)
    if (args.size != 3 || !isApplicableCommand(params)) None
    else {
      args match {
        case Seq(
              parser1.Jsonized(t1),
              parser2.Jsonized(t2),
              parser3.Jsonized(t3)
            ) =>
          Some((t1, t2, t3))
        case _ => None
      }
    }
  }

}

object Argument {

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

}
