package scala.meta.internal.metals

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
      scalaMetaSymbols = symbol.toScalaMetaSymbols(
        getPackageAndThisSymbols(path, position)
      )
      _ = scribe.debug(
        s"looking for definition for scaladoc symbol: $symbol considering alternatives: ${scalaMetaSymbols
            .mkString(", ")}"
      )
      definitionResult <- scalaMetaSymbols.collectFirst { sym =>
        Try(destinationProvider.fromSymbol(sym, Some(path))) match {
          case Success(Some(value)) => value
        }
      }
    } yield definitionResult
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

  private def getPackageAndThisSymbols(
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
  def toScalaMetaSymbols(contextSymbols: => ContextSymbols): List[String] =
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

      symbol.last match {
        case '#' | '.' | '/' => withPrefixes
        case '$' =>
          withPrefixes.flatMap(sym =>
            List(s"${sym.dropRight(1)}.", s"${sym.dropRight(1)}().")
          )
        case '!' => withPrefixes.flatMap(sym => List(s"${sym.dropRight(1)}#"))
        case _ =>
          withPrefixes.flatMap(sym => List(s"$sym#", s"$sym.", s"$sym()."))
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
