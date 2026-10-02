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
import scala.meta.internal.docstrings.WikiLink
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags
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
      contextSymbols = getContext(path, position, isScala3)
      symbolGroups = symbol.toScalaMetaSymbolGroups(contextSymbols)
      _ = scribe.debug(
        s"looking for definition for scaladoc symbol: $symbol considering alternatives: ${symbolGroups.flatten
            .map(_.showSymbol)
            .mkString(", ")}"
      )
      definitionResult <- symbolGroups.view
        .flatMap(resolveGroup(_, path, isScala3))
        .headOption
    } yield definitionResult
  }

  // Scala 2 binds an ambiguous `[[Name]]` type-before-value (first match);
  // Scala 3 first-in-source-order among same-file candidates (scalameta/metals#3383).
  private def resolveGroup(
      group: List[ScalaDocLinkSymbol],
      path: AbsolutePath,
      isScala3: Boolean,
  ): Option[DefinitionResult] = {
    def firstMatch: Option[DefinitionResult] =
      group.collectFirst { sym =>
        search(sym, path) match {
          case Some(value) => value
        }
      }
    if (!isScala3) firstMatch
    else {
      val resolved = group
        .flatMap(sym => search(sym, path))
        .filter(_.locations.asScala.nonEmpty)
      resolved match {
        case Nil => None
        case single :: Nil => Some(single)
        case many =>
          val sameFile =
            many
              .map(_.locations.asScala.head.getUri)
              .distinct
              .lengthCompare(1) == 0
          if (sameFile)
            // minBy keeps the FIRST minimum, so equal positions (synthetic
            // companions) fall back to descriptor precedence.
            Some(many.minBy { result =>
              val start = result.locations.asScala.head.getRange.getStart
              (start.getLine, start.getCharacter)
            })
          else firstMatch
      }
    }
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
          results.head.querySymbol,
        )
      )
  }

  private def extractScalaDocLinkAtPos(
      buffer: String,
      position: Position,
      isScala3: Boolean,
  ) =
    for {
      tokens <- buffer.safeTokenize(Trees.defaultTokenizerDialect).toOption
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
      isScala3: Boolean,
  ): ContextSymbols = {
    // Encode a name as its SemanticDB descriptor: backticked by its CHARACTERS
    // (dots/spaces), never for keywords (scalameta/metals#3383).
    def descName(value: String): String = {
      def plain(c: Char) = c.isLetterOrDigit || c == '_' || c == '$'
      def operator(c: Char) = "!#%&*+-/:<=>?@\\^|~".contains(c)
      if (value.nonEmpty && (value.forall(plain) || value.forall(operator)))
        value
      else s"`$value`"
    }
    def extractName(ref: Term): String =
      ref match {
        case Term.Select(qual, name) =>
          s"${extractName(qual)}/${descName(name.value)}"
        case name: Term.Name => descName(name.value)
        case _ => ""
      }

    // The Scala 3 synthetic object owning this file's top-level members
    // (`Main.scala` → `Main$package`) (scalameta/metals#3383).
    val filePackageObject: String = {
      val filename = path.filename
      val dot = filename.lastIndexOf('.')
      val stem = if (dot > 0) filename.substring(0, dot) else filename
      descName(s"$stem$$package")
    }

    def enclosedChild(tree: Tree): Option[Tree] =
      tree.children
        .find { child =>
          child.pos.start <= pos.start && pos.start <= child.pos.end
        }

    // The owner context of ONE tree node; non-owner nodes are transparent
    // (scalameta/metals#3383).
    def contextOf(
        tree: Tree,
        enclosingPackagePath: String,
        enclosingSymbol: String,
        alternativeEnclosingSymbol: Option[String],
    ): (String, String, Option[String]) = {
      // A Scala 3 top-level member's owner is the file's synthetic `$package`
      // object rather than the bare package (scalameta/metals#3383).
      val ownerPrefix =
        if (isScala3 && enclosingSymbol.isEmpty) s"$filePackageObject."
        else enclosingSymbol
      tree match {
        case Pkg(name, _) =>
          (
            s"$enclosingPackagePath${extractName(name)}/",
            enclosingSymbol,
            None,
          )
        case d: Pkg.Object =>
          // A package object extends the package and owns a `package.`
          // template (scalameta/metals#3383).
          (
            s"$enclosingPackagePath${descName(d.name.value)}/",
            s"${enclosingSymbol}package.",
            None,
          )
        case d: Defn.Object =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}.",
            None,
          )
        case d: Defn.Class =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            None,
          )
        case d: Defn.Trait =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            None,
          )
        case d: Defn.Enum =>
          (
            enclosingPackagePath,
            s"$enclosingSymbol${descName(d.name.value)}#",
            Some(s"$enclosingSymbol${descName(d.name.value)}."),
          )
        case d: Defn.Given if d.name.value.nonEmpty =>
          // A named given owns members under `name#` but is itself the value
          // `name.`; offer both. At Scala 3 top level it lives in `$package`
          // (scalameta/metals#3383).
          (
            enclosingPackagePath,
            s"$ownerPrefix${descName(d.name.value)}#",
            Some(s"$ownerPrefix${descName(d.name.value)}."),
          )
        case (_: Defn.Def | _: Defn.Val | _: Defn.Var | _: Defn.Type |
            _: Defn.Given | _: Defn.GivenAlias | _: Defn.ExtensionGroup)
            if isScala3 && enclosingSymbol.isEmpty =>
          // A Scala 3 top-level member's owner is the file's synthetic
          // `<file>$package` object (scalameta/metals#3383).
          (
            enclosingPackagePath,
            s"$filePackageObject.",
            None,
          )
        // Includes an anonymous given (`given_<type>` can't be rebuilt from
        // syntax) — a safe miss (scalameta/metals#3383).
        case _ =>
          (enclosingPackagePath, enclosingSymbol, alternativeEnclosingSymbol)
      }
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
        contextOf(
          tree,
          enclosingPackagePath,
          enclosingSymbol,
          alternativeEnclosingSymbol,
        )
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
          // A leading doc comment encloses no child; resolve against the
          // member FOLLOWING it, not the enclosing owner (scalameta/metals#3383).
          tree.children.find(_.pos.start >= pos.start) match {
            case Some(member0) =>
              // scalameta wraps package statements in a `Pkg.Body`; descend
              // through the wrapper (scalameta/metals#3383).
              val member = member0 match {
                case body: Pkg.Body =>
                  body.children
                    .find(_.pos.start >= pos.start)
                    .getOrElse(member0)
                case other => other
              }
              contextOf(
                member,
                enclosingPackagePath1,
                enclosingSymbol1,
                alternativeEnclosingSymbol1,
              )
            case None =>
              (
                enclosingPackagePath1,
                enclosingSymbol1,
                alternativeEnclosingSymbol1,
              )
          }
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

  def toScalaMetaSymbols(
      contextSymbols: => ContextSymbols
  ): List[ScalaDocLinkSymbol] =
    toScalaMetaSymbolGroups(contextSymbols).flatten

  // One precedence-ordered candidate group per link interpretation
  // (`this.`-relative, package-relative, fully-qualified) (scalameta/metals#3383).
  def toScalaMetaSymbolGroups(
      contextSymbols: => ContextSymbols
  ): List[List[ScalaDocLinkSymbol]] =
    if (rawSymbol.isEmpty()) List.empty
    else {
      val (symbol0, symbolType) = symbolWithType
      val symbol = fixPackages(symbol0)

      val optIndexOfSlash =
        symbol.findIndicesOf(List('/')).headOption
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

      withPrefixes.map { sym =>
        symbolType match {
          case ScalaDocLink.SymbolType.Method =>
            List(MethodSymbol(sym))
          case ScalaDocLink.SymbolType.Value =>
            List(StringSymbol(s"$sym."), MethodSymbol(sym))
          case ScalaDocLink.SymbolType.Type =>
            List(StringSymbol(s"$sym#"))
          case ScalaDocLink.SymbolType.Any =>
            List(
              StringSymbol(s"$sym#"),
              StringSymbol(s"$sym."),
              MethodSymbol(sym),
            )
        }
      }
    }

  private def symbolWithType: (String, ScalaDocLink.SymbolType) =
    rawSymbol.findIndicesOf(List('(', '[')).headOption match {
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
    mtags.Symbol.guessFromPath(symbol, isScala3).value
}

object ScalaDocLink {

  // Extraction is shared with the renderer (`WikiLink`), so source
  // go-to-definition navigates exactly the links the renderer renders —
  // including `[[[ ... ]]]` and a link's title (scalameta/metals#3383).
  def atOffset(
      text: String,
      offset: Int,
      isScala3: Boolean,
  ): Option[ScalaDocLink] =
    WikiLink.atOffset(text, offset).map(ScalaDocLink(_, isScala3))

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
