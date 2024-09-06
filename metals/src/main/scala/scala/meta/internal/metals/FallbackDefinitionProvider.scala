package scala.meta.internal.metals

import scala.meta.Term
import scala.meta.Type
import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.tokens.Token

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.{Range => LspRange}

class FallbackDefinitionProvider(
    trees: Trees,
    index: GlobalSymbolIndex,
) {

  /**
   *  Tries to find an identifier token at the current position to
   *  guess the symbol to find and then searches for it in the symbol index.
   *  This is the last possibility for finding the definition.
   *
   * @param path path of the current file
   * @param pos position we are searching for
   * @return possible definition locations based on exact symbol search
   */
  def search(
      path: AbsolutePath,
      pos: Position,
      isScala3: Boolean,
  ): Option[DefinitionResult] = {
    val range = new LspRange(pos, pos)

    val defResult = for {
      tokens <- trees.tokenized(path)
      ident <- tokens.collectFirst {
        case id: Token.Ident if id.pos.encloses(range) => id
      }
      tree <- trees.get(path)
    } yield {
      lazy val nameTree = trees.findLastEnclosingAt(path, pos)

      // for sure is not a class/trait/enum if we access it via select
      lazy val isInSelectPosition =
        nameTree.flatMap(_.parent).exists(isInSelect(_, range))

      lazy val isInTypePosition = nameTree.exists(_.is[Type.Name])

      def guessObjectOrClass(parts: List[String]) = {
        val symbolPrefix = mtags.Symbol
          .guessSymbolFromParts(parts, isScala3)
          .value
        if (isInSelectPosition) List(symbolPrefix + ".")
        else if (isInTypePosition) List(symbolPrefix + "#")
        else List(".", "#", "().").map(ending => symbolPrefix + ending)
      }

      // Get all select parts to build symbol from it later
      val proposedNameParts =
        nameTree
          .flatMap(_.parent)
          .map {
            case tree: Term.Select if nameTree.contains(tree.name) =>
              nameFromSelect(tree, Nil)
            case _ => List(ident.value)
          }
          .getOrElse(List(ident.value))

      val currentPackageStatements = trees
        .packageStatementsAtPosition(path, pos) match {
        case None => List("_empty_")
        case Some(value) =>
          // generate packages from all the package statements
          value.foldLeft(Seq.empty[String]) { case (pre, suffix) =>
            if (pre.isEmpty) List(suffix) else pre :+ (pre.last + "." + suffix)
          }
      }

      val proposedCurrentPackageSymbols =
        currentPackageStatements.flatMap(pkg =>
          guessObjectOrClass(
            (pkg.split("\\.").toList ++ proposedNameParts)
          )
        )

      // First name in select is the one that must be imported or in scope
      val probablyImported = proposedNameParts.headOption.getOrElse(ident.value)

      // Search for imports that match the current symbol
      val proposedImportedSymbols =
        tree.collect {
          case imp @ Import(importers)
              // imports should be in the same scope as the current position
              if imp.parent.exists(_.pos.encloses(range)) =>
            importers.collect { case Importer(ref: Term, p) =>
              val packageSyntax = ref.toString.split("\\.").toList
              p.collect {
                case Importee.Name(name) if name.value == probablyImported =>
                  guessObjectOrClass(packageSyntax ++ proposedNameParts)

                case Importee.Rename(name, renamed)
                    if renamed.value == probablyImported =>
                  guessObjectOrClass(
                    packageSyntax ++ (name.value +: proposedNameParts.drop(1))
                  )
                case _: Importee.Wildcard =>
                  guessObjectOrClass(packageSyntax ++ proposedNameParts)

              }.flatten
            }.flatten
        }.flatten

      val standardScalaImports = guessObjectOrClass(
        List("scala", "Predef") ++ proposedNameParts
      )
      val fullyScopedName =
        guessObjectOrClass(proposedNameParts)

      def findInIndex(proposedSymbol: String) = {
        index
          .definition(mtags.Symbol(proposedSymbol))
          // Make sure we don't return unrelated definitions
          .filter { _.definitionSymbol.value == proposedSymbol }
      }
      val nonLocalGuesses =
        (proposedImportedSymbols ++ fullyScopedName ++ standardScalaImports).distinct
          .flatMap { proposedSymbol =>
            findInIndex(proposedSymbol)
          }

      def toDefinition(guesses: List[mtags.SymbolDefinition]) = {

        DefinitionResult(
          guesses
            .flatMap(guess =>
              guess.range.map(range =>
                new Location(guess.path.toURI.toString(), range.toLsp)
              )
            )
            .asJava,
          ident.value,
          None,
          None,
          ident.value,
        )
      }
      val result = if (nonLocalGuesses.nonEmpty) {
        Some(toDefinition(nonLocalGuesses))
      } else {
        // otherwise might be symbol in a local package, starting from enclosing
        proposedCurrentPackageSymbols.reverse
          .map(proposedSymbol => findInIndex(proposedSymbol))
          .collectFirst { case Some(dfn) =>
            toDefinition(List(dfn))
          }
      }

      result.foreach { _ =>
        scribe.warn(
          s"Could not find '${ident.value}' using presentation compiler nor semanticdb. " +
            s"Trying to guess the definition using available information from local class context. "
        )
      }
      result
    }

    defResult.flatten
  }

  private def isInSelect(tree: Tree, range: LspRange): Boolean = tree match {
    case Type.Select(qual, _) if qual.pos.encloses(range) => true
    case Term.Select(qual, _) if qual.pos.encloses(range) => true
    case Term.Select(_, _) => tree.parent.exists(isInSelect(_, range))
    case _: Importer => true
    case _ => false
  }

  private def nameFromSelect(tree: Tree, acc: List[String]): List[String] = {
    tree match {
      case Term.Select(qualifier, name) =>
        nameFromSelect(qualifier, name.value +: acc)
      case Term.Name(value) => value +: acc
      case _ => Nil
    }
  }

}
