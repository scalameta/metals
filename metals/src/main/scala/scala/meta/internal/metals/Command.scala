package scala.meta.internal.metals

import java.net.URLEncoder

import scala.reflect.ClassTag
import scala.util.matching.Regex

import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.{lsp4j => l}

sealed trait BaseCommand {
  def id: String
  def title: String
  def description: String
  def arguments: String

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

  def toExecuteCommandParams(): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      id,
      List[Object]().asJava
    )
  }

  def toCommandLink(
      commandInHtmlFormat: CommandHTMLFormat
  ): String =
    commandInHtmlFormat match {
      case CommandHTMLFormat.VSCode => s"command:metals.$id"
      case CommandHTMLFormat.Sublime => s"href='subl:$id {}'"
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

  def toCommandLink(
      argument: T,
      commandInHtmlFormat: CommandHTMLFormat
  ): String = {
    val param = s"[${argument.toJson.toString()}]"
    commandInHtmlFormat match {
      case CommandHTMLFormat.VSCode =>
        s"command:metals.$id?${URLEncoder.encode(param)}"
      case CommandHTMLFormat.Sublime =>
        s"href='subl:$id {${URLEncoder.encode(param)}}'"
    }
  }
  def toLSP(argument: T): l.Command =
    new l.Command(title, id, List(argument.toJson.asInstanceOf[AnyRef]).asJava)

  def toExecuteCommandParams(argument: T): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      id,
      List[Object](
        argument.toJson
      ).asJava
    )
  }
}

case class ParametrizedCommand2[T1: ClassTag, T2: ClassTag](
    id: String,
    title: String,
    description: String,
    arguments: String
) extends BaseCommand {

  private val parser1 = new JsonParser.Of[T1]
  private val parser2 = new JsonParser.Of[T2]

  def unapply(params: l.ExecuteCommandParams): Option[(T1, T2)] = {
    val args = Option(params.getArguments()).toList.flatMap(_.asScala)
    if (args.size != 2 || !isApplicableCommand(params)) None
    else {
      (args(0), args(1)) match {
        case (parser1.Jsonized(t1), parser2.Jsonized(t2)) =>
          Option((t1, t2))
        case _ => None
      }
    }
  }

  def toLSP(argument1: T1, argument2: T2): l.Command =
    new l.Command(
      title,
      id,
      List(
        argument1.toJson.asInstanceOf[AnyRef],
        argument2.toJson.asInstanceOf[AnyRef]
      ).asJava
    )

  def toExecuteCommandParams(
      argument1: T1,
      argument2: T2
  ): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      id,
      List[Object](
        argument1.toJson,
        argument2.toJson
      ).asJava
    )
  }

  def toCommandLink(
      argument1: T1,
      argument2: T2,
      commandInHtmlFormat: CommandHTMLFormat
  ): String = {
    val param =
      s"[${argument1.toJson.toString()}, ${argument2.toJson.toString()}]"
    commandInHtmlFormat match {
      case CommandHTMLFormat.VSCode =>
        s"command:metals.$id?${URLEncoder.encode(param)}"
      case CommandHTMLFormat.Sublime =>
        s"href='subl:$id {${URLEncoder.encode(param)}}'"
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

  def toLSP(arguments: T*): l.Command =
    new l.Command(
      title,
      id,
      arguments.map(_.toJson.asInstanceOf[AnyRef]).asJava
    )

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

  def toExecuteCommandParams(argument: T*): l.ExecuteCommandParams = {
    new l.ExecuteCommandParams(
      id,
      argument.map(_.toJson.asInstanceOf[AnyRef]).asJava
    )
  }
}
