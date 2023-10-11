package scala.meta.internal.metals

import scala.collection.mutable.ListBuffer
import scala.util.Success
import scala.util.Try

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token.Comment

import org.eclipse.lsp4j.TextDocumentPositionParams

class ScaladocDefinitionProvider(
    buffers: Buffers,
    trees: Trees,
    destinationProvider: DestinationProvider,
) {

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
  ): Option[DefinitionResult] = {
    for {
      buffer <- buffers.get(path)
      position <- params.getPosition().toMeta(Input.String(buffer))
      symbol <- extractScalaDocLinkAtPos(buffer, position)
      contextSymbols = getContext(path, position)
      scalaMetaSymbols = symbol.toScalaMetaSymbols(contextSymbols)
      _ = scribe.debug(
        s"looking for definition for scaladoc symbol: $symbol considering alternatives: ${scalaMetaSymbols
            .map(_.showSymbol)
            .mkString(", ")}"
      )
      definitionResult <- scalaMetaSymbols.collectFirst { sym =>
        search(sym, path) match {
          case Some(value) => value
        }
      }
    } yield definitionResult
  }

  private def search(symbol: ScalaDocLinkSymbol, path: AbsolutePath) =
    symbol match {
      case method: MethodSymbol => findAllOverLoadedMethods(method, path)
      case StringSymbol(symbol) =>
        Try(destinationProvider.fromSymbol(symbol, Some(path))).toOption.flatten
          .filter(_.symbol == symbol)
    }

  private def findAllOverLoadedMethods(
      method: MethodSymbol,
      path: AbsolutePath,
  ) = {
    var ident: Int = 0
    val results: ListBuffer[DefinitionResult] = new ListBuffer
    var ok: Boolean = true
    while (ok) {
      val currentSymbol = method.symbol(ident)
      Try(
        destinationProvider.fromSymbol(currentSymbol, Some(path))
      ) match {
        case Success(Some(value)) if value.symbol == currentSymbol =>
          ident += 1
          results.addOne(value)
        case _ => ok = false
      }
    }

    if (results.isEmpty) None
    else
      Some(
        new DefinitionResult(
          results.toList.flatMap(_.locations.asScala).asJava,
          results.head.symbol,
          None,
          None,
        )
      )
  }

  private def extractScalaDocLinkAtPos(
      buffer: String,
      position: Position,
  ) =
    for {
      tokens <- Trees.defaultTokenizerDialect(buffer).tokenize.toOption
      comment <- tokens.collectFirst {
        case token: Comment if token.pos.encloses(position) => token
      }
      if comment.text.startsWith("/**") && comment.text.endsWith("*/")
      offset = position.start - comment.start
      symbol <- ScalaDocLink.atOffset(comment.text, offset)
    } yield symbol

  private def getContext(
      path: AbsolutePath,
      pos: Position,
  ): ContextSymbols = {
    def extractName(ref: Term): String =
      ref match {
        case Term.Select(qual, name) => s"${extractName(qual)}/${name.value}"
        case Term.Name(name) => name
        case _ => ""
      }

    def enclosedChild(tree: Tree): Option[Tree] =
      tree.children
        .find { child =>
          child.pos.start <= pos.start && pos.start <= child.pos.end
        }

    def loop(
        tree: Tree,
        packageParts: String = "",
        otherParts: String = "",
    ): (String, String) = {
      val (packageParts1, otherParts1) =
        tree match {
          case Pkg(name, _) =>
            (s"$packageParts${extractName(name)}/", otherParts)
          case d: Defn.Object => (packageParts, s"$otherParts${d.name.value}.")
          case d: Defn.Class => (packageParts, s"$otherParts${d.name.value}#")
          case _ => (packageParts, otherParts)
        }

      enclosedChild(tree).map(loop(_, packageParts1, otherParts1)).getOrElse {
        (packageParts1, otherParts1)
      }
    }

    trees
      .get(path)
      .map { tree =>
        val (packagePart, otherParts) = loop(tree)
        ContextSymbols(packagePart, otherParts)
      }
      .getOrElse(ContextSymbols.empty)

  }

}

case class ScalaDocLink(value: String) {
  def toScalaMetaSymbols(
      contextSymbols: => ContextSymbols
  ): List[ScalaDocLinkSymbol] =
    if (value.isEmpty()) List.empty
    else {
      val symbol = symbolWithFixedPackages
      val indexOfDot = value.indexOf(".")
      def all = List(symbol) ++
        contextSymbols.withThis(symbol) ++
        contextSymbols.withPackage(symbol)
      val withPrefixes: List[String] =
        if (indexOfDot < 0) all
        else {
          symbol.splitAt(indexOfDot + 1) match {
            case ("this/", rest) => contextSymbols.withThis(rest)
            case ("package/", rest) => contextSymbols.withPackage(rest)
            case _ if symbol.contains("/") => List(symbol)
            case _ => all
          }
        }

      List(symbol.indexOf("("), symbol.indexOf("[")).filter(_ >= 0) match {
        case Nil =>
          symbol.last match {
            case '#' | '.' | '/' => withPrefixes.map(StringSymbol(_))
            case '$' =>
              withPrefixes.flatMap(sym =>
                List(
                  StringSymbol(s"${sym.dropRight(1)}."),
                  MethodSymbol(s"${sym.dropRight(1)}"),
                )
              )
            case '!' =>
              withPrefixes.flatMap(sym =>
                List(StringSymbol(s"${sym.dropRight(1)}#"))
              )
            case _ =>
              withPrefixes.flatMap(sym =>
                List(
                  StringSymbol(s"$sym#"),
                  StringSymbol(s"$sym."),
                  MethodSymbol(sym),
                )
              )
          }
        case list =>
          val toDrop = symbol.length - list.min
          withPrefixes.flatMap(sym => List(MethodSymbol(sym.dropRight(toDrop))))
      }
    }

  private def symbolWithFixedPackages = {
    val fixedPackages =
      value
        .split("\\.")
        .map { str =>
          if (str.headOption.exists(_.isLower)) s"$str/"
          else s"$str."
        }
        .mkString
    if (value.endsWith(".")) fixedPackages
    else fixedPackages.dropRight(1)
  }
}

object ScalaDocLink {
  private val irrelevantWhite = "[ \\n\\t\\r]"
  private val regex = s"\\[\\[$irrelevantWhite*(.*?)$irrelevantWhite*\\]\\]".r
  def atOffset(text: String, offset: Int): Option[ScalaDocLink] =
    regex.findAllMatchIn(text).collectFirst {
      case m if m.start(1) <= offset && offset <= m.end(1) =>
        ScalaDocLink(m.group(1))
    }
}

case class ContextSymbols(
    packageSymbol: Option[String],
    thisSymbol: Option[String],
) {
  def withThis(sym: String): List[String] = thisSymbol.map(_ ++ sym).toList
  def withPackage(sym: String): List[String] =
    packageSymbol.map(_ ++ sym).toList
}

object ContextSymbols {
  def apply(packageSymbol: String, thisSymbol: String): ContextSymbols = {
    val packageSymbol1 =
      if (packageSymbol.nonEmpty) packageSymbol
      else "_empty_/"
    val thisSymbol1 =
      Option.when(thisSymbol.nonEmpty)(packageSymbol1 ++ thisSymbol)
    ContextSymbols(Some(packageSymbol1), thisSymbol1)
  }

  def empty: ContextSymbols = ContextSymbols(None, None)

}

sealed trait ScalaDocLinkSymbol {
  def showSymbol: String
}
case class StringSymbol(symbol: String) extends ScalaDocLinkSymbol {
  override def showSymbol: String = symbol
}
case class MethodSymbol(prefixSymbol: String) extends ScalaDocLinkSymbol {
  def symbol(i: Int): String =
    i match {
      case 0 => s"$prefixSymbol()."
      case _ => s"$prefixSymbol(+$i)."
    }
  override def showSymbol: String = s"$prefixSymbol(+n)."
}
