package scala.meta.internal.metals

import scala.annotation.tailrec
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
import scala.meta.internal.mtags.KeywordWrapper
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
      isScala3: Boolean,
  ): Option[DefinitionResult] = {
    for {
      buffer <- buffers.get(path)
      position <- params.getPosition().toMeta(Input.String(buffer))
      symbol <- extractScalaDocLinkAtPos(buffer, position, isScala3)
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
      isScala3: Boolean,
  ) =
    for {
      tokens <- Trees.defaultTokenizerDialect(buffer).tokenize.toOption
      comment <- tokens.collectFirst {
        case token: Comment if token.pos.encloses(position) => token
      }
      if comment.text.startsWith("/**") && comment.text.endsWith("*/")
      offset = position.start - comment.start
      symbol <- ScalaDocLink.atOffset(comment.text, offset, isScala3)
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
        enclosingPackagePath: String = "",
        enclosingSymbol: String = "",
        alternativeEnclosingSymbol: Option[String] = None,
    ): (String, String, Option[String]) = {
      val (
        enclosingPackagePath1,
        enclosingSymbol1,
        alternativeEnclosingSymbol1,
      ) =
        tree match {
          case Pkg(name, _) =>
            (
              s"$enclosingPackagePath${extractName(name)}/",
              enclosingSymbol,
              None,
            )
          case d: Defn.Object =>
            (enclosingPackagePath, s"$enclosingSymbol${d.name.value}.", None)
          case d: Defn.Class =>
            (enclosingPackagePath, s"$enclosingSymbol${d.name.value}#", None)
          case d: Defn.Trait =>
            (enclosingPackagePath, s"$enclosingSymbol${d.name.value}#", None)
          case d: Defn.Enum =>
            (
              enclosingPackagePath,
              s"$enclosingSymbol${d.name.value}#",
              Some(s"$enclosingSymbol${d.name.value}."),
            )
          case d: Defn.Given =>
            (enclosingPackagePath, s"$enclosingSymbol${d.name.value}#", None)
          case _ =>
            (enclosingPackagePath, enclosingSymbol, alternativeEnclosingSymbol)
        }

      enclosedChild(tree)
        .map(
          loop(
            _,
            enclosingPackagePath1,
            enclosingSymbol1,
            alternativeEnclosingSymbol1,
          )
        )
        .getOrElse {
          (enclosingPackagePath1, enclosingSymbol1, alternativeEnclosingSymbol1)
        }
    }

    trees
      .get(path)
      .map { tree =>
        val (
          enclosingPackagePath,
          enclosingSymbol,
          alternativeEnclosingSymbol,
        ) =
          loop(tree)
        ContextSymbols(
          enclosingPackagePath,
          enclosingSymbol,
          alternativeEnclosingSymbol,
        )
      }
      .getOrElse(ContextSymbols.empty)

  }

}

case class ScalaDocLink(rawSymbol: String, isScala3: Boolean) {
  private val keywordWrapper =
    if (isScala3) KeywordWrapper.Scala3 else KeywordWrapper.Scala2

  def toScalaMetaSymbols(
      contextSymbols: => ContextSymbols
  ): List[ScalaDocLinkSymbol] =
    if (rawSymbol.isEmpty()) List.empty
    else {
      val (symbol0, symbolType) = symbolWithType
      val symbol = fixPackages(symbol0)

      val optIndexOfSlash =
        ScalaDocLink.findIndicesOf(symbol, List('/')).headOption
      val withPrefixes: List[String] =
        optIndexOfSlash match {
          case Some(indexOfSlash) =>
            symbol.splitAt(indexOfSlash + 1) match {
              // raw symbol [[this.<symbol>]], e.g. [[this.someMethod]]
              // we substitute `this.` for `enclosingSymbol`
              case ("this/", rest) => contextSymbols.withThis(rest)
              // raw symbol [[package.<symbol>]], e.g. [[package.SomeObject.someMethod]]
              // we substitute `package.` for `enclosingPackagePath`
              case ("package/", rest) => contextSymbols.withPackage(rest)
              // the symbol has some package defined e.g. [[a.b.SomeThing]]
              // we search for `package.<symbol>` and `<symbol>`
              case _ => contextSymbols.withPackage(symbol) ++ List(symbol)
            }
          // symbol has no package defined e.g. [[someMethod]]
          // we search for [[this.<symbol>]] and [[package.<symbol>]]
          case None =>
            contextSymbols.withThis(symbol) ++
              contextSymbols.withPackage(symbol)
        }

      symbolType match {
        case ScalaDocLink.SymbolType.Method =>
          withPrefixes.flatMap(sym => List(MethodSymbol(sym)))
        case ScalaDocLink.SymbolType.Value =>
          withPrefixes.flatMap(sym =>
            List(StringSymbol(s"$sym."), MethodSymbol(sym))
          )
        case ScalaDocLink.SymbolType.Type =>
          withPrefixes.flatMap(sym => List(StringSymbol(s"$sym#")))
        case ScalaDocLink.SymbolType.Any =>
          withPrefixes.flatMap(sym =>
            List(
              StringSymbol(s"$sym#"),
              StringSymbol(s"$sym."),
              MethodSymbol(sym),
            )
          )
      }
    }

  private def symbolWithType: (String, ScalaDocLink.SymbolType) =
    ScalaDocLink.findIndicesOf(rawSymbol, List('(', '[')).headOption match {
      case Some(index) =>
        val toDrop = rawSymbol.length() - index
        (rawSymbol.dropRight(toDrop), ScalaDocLink.SymbolType.Method)
      case None =>
        rawSymbol.last match {
          // e.g. [[a.b.Foo$]]
          // forces link to refer to a value (an object, a value, a given)
          case '$' => (rawSymbol.dropRight(1), ScalaDocLink.SymbolType.Value)
          // e.g. [[a.b.Foo!]]
          // forces link to refer to a type (a class, a type alias, a type member)
          case '!' => (rawSymbol.dropRight(1), ScalaDocLink.SymbolType.Type)
          // no meaningful suffix, e.g. [[a.b.Foo]]
          // we search for types then values
          case _ => (rawSymbol, ScalaDocLink.SymbolType.Any)
        }
    }

  /**
   * Replace `.` with `\` for packages and wrap with backticks when needed.
   * e.g. a.b.c.A.O to a/b/c/A.O
   */
  private def fixPackages(symbol: String) =
    ScalaDocLink
      .splitAt(symbol, '.')
      .map { str =>
        // drop `\` used for escaping and wrap in backticks when needed
        // e.g. [[Foo\\.bar]] -> [[`Foo.bar`]]
        val name = keywordWrapper.backtickWrap(
          str.replace("\\", ""),
          Set("this", "package"),
        )
        if (
          str.headOption.exists(_.isLower) ||
          (name.length > 1 && name.head == '`' && name.charAt(1).isLower)
        ) s"$name/"
        else s"$name."
      }
      .mkString
      .dropRight(1)
}

object ScalaDocLink {
  private val irrelevantWhite = "[ \\n\\t\\r]"
  private val regex = s"\\[\\[$irrelevantWhite*(.*?)$irrelevantWhite*\\]\\]".r
  def atOffset(
      text: String,
      offset: Int,
      isScala3: Boolean,
  ): Option[ScalaDocLink] =
    regex.findAllMatchIn(text).collectFirst {
      case m if m.start(1) <= offset && offset <= m.end(1) =>
        ScalaDocLink(m.group(1), isScala3)
    }

  def splitAt(text: String, c: Char): List[String] = {
    val indices = findIndicesOf(text, List(c))
    splitAt(text, indices)
  }

  @tailrec
  private def splitAt(
      text: String,
      indices: List[Int],
      offset: Int = 0,
      acc: List[String] = List.empty,
  ): List[String] = {
    indices match {
      case i :: rest =>
        val (part1, part2) = text.splitAt(i - offset)
        splitAt(part2.tail, rest, i + 1, part1 :: acc)
      case _ => (text :: acc).reverse
    }
  }

  def findIndicesOf(text: String, symbols: List[Char]): List[Int] = {
    @tailrec
    def loop(
        index: Int,
        afterEscape: Boolean,
        inBackticks: Boolean,
        acc: List[Int],
    ): List[Int] =
      if (index >= text.length()) acc.reverse
      else {
        val c = text.charAt(index)
        val newAcc =
          if (symbols.contains(c) && !inBackticks && !afterEscape) index :: acc
          else acc
        loop(
          index + 1,
          afterEscape = c == '\\',
          inBackticks = c == '`' ^ inBackticks,
          acc = newAcc,
        )
      }
    loop(index = 0, afterEscape = false, inBackticks = false, acc = List.empty)
  }

  sealed trait SymbolType
  object SymbolType {
    case object Method extends SymbolType
    case object Value extends SymbolType
    case object Type extends SymbolType
    case object Any extends SymbolType
  }
}

case class ContextSymbols(
    enclosingPackagePath: Option[String],
    enclosingSymbol: Option[String],
    alternativeEnclosingSymbol: Option[String],
) {
  def withThis(sym: String): List[String] =
    enclosingSymbol.map(_ ++ sym).toList ++ alternativeEnclosingSymbol
      .map(_ ++ sym)
      .toList
  def withPackage(sym: String): List[String] =
    enclosingPackagePath.map(_ ++ sym).toList
}

object ContextSymbols {
  def apply(
      enclosingPackagePath: String,
      enclosingSymbol: String,
      alternativeEnclosingSymbol: Option[String],
  ): ContextSymbols = {
    val packageSymbol1 =
      if (enclosingPackagePath.nonEmpty) enclosingPackagePath
      else "_empty_/"
    val thisSymbol1 =
      Option.when(enclosingSymbol.nonEmpty)(packageSymbol1 ++ enclosingSymbol)
    val thisSymbolAlt =
      alternativeEnclosingSymbol.map(packageSymbol1 ++ _)
    ContextSymbols(Some(packageSymbol1), thisSymbol1, thisSymbolAlt)
  }

  def empty: ContextSymbols = ContextSymbols(None, None, None)

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
