package scala.meta.internal.metals

import scala.reflect.ClassTag
import scala.util.matching.Regex

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

sealed trait BaseCommand {
  def id: String
  def title: String
  def description: String
  def arguments: String

  def toLSP(arguments: List[AnyRef]): l.Command =
    new l.Command(title, id, arguments.asJava)

  protected def isApplicableCommand(params: l.ExecuteCommandParams): Boolean =
    params.getCommand.stripPrefix("metals.") == id
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

case class ParametrizedCommand[T: ClassTag](
    id: String,
    title: String,
    description: String,
    arguments: String
) extends BaseCommand {

  private val parser = new JsonParser.Of[T]

  def unapply(params: l.ExecuteCommandParams): Option[T] = {
    val args = Option(params.getArguments()).toList.flatMap(_.asScala)
    if (args.size != 1 || !isApplicableCommand(params)) None
    else {
      args(0) match {
        case parser.Jsonized(t1) =>
          Option(t1)
        case _ => None
      }
    }
  }

}

case class ListParametrizedCommand[T: ClassTag](
    id: String,
    title: String,
    description: String,
    arguments: String
) extends BaseCommand {

  private val parser = new JsonParser.Of[T]

  def unapply(params: l.ExecuteCommandParams): Option[List[Option[T]]] = {
    if (!isApplicableCommand(params)) None
    else {
      val args = Option(params.getArguments()).toList
        .flatMap(_.asScala)
        .map {
          case parser.Jsonized(t) => Option(t)
          case _ => None
        }
      Some(args)
    }
  }
}
