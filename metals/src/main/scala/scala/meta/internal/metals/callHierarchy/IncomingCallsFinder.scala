package scala.meta.internal.metals.callHierarchy

import scala.meta.Defn
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ResolvedSymbolOccurrence
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.OriginalTree
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j

class IncomingCallsFinder(definition: DefinitionProvider, trees: Trees)
    extends CallHierarchyHelpers {
  private def getCallResultFromPosition(
      source: AbsolutePath,
      doc: TextDocument,
      pos: lsp4j.Position,
      definitionNameRange: lsp4j.Range,
      fromRange: lsp4j.Range,
      predicate: SymbolOccurrence => Boolean = _ => true,
  ): List[FindIncomingCallsResult] =
    definition
      .positionOccurrences(source, pos, doc)
      .collect {
        case ResolvedSymbolOccurrence(_, Some(occurence))
            if predicate(occurence) =>
          FindIncomingCallsResult(
            occurence,
            definitionNameRange,
            List(fromRange),
          )
      }

  private def extractNameAndPathsFromPat(
      pat: Pat,
      indices: Vector[Int] = Vector.empty,
  ): List[(Name, Vector[Int])] =
    pat match {
      case v: Pat.Var => List(v.name -> indices)
      case tuple: Pat.Tuple =>
        tuple.args.zipWithIndex.flatMap { case (p, i) =>
          extractNameAndPathsFromPat(p, indices :+ i)
        }
      case extract: Pat.Extract =>
        extract.args.zipWithIndex.flatMap { case (p, i) =>
          extractNameAndPathsFromPat(p, indices :+ i)
        }
      case _ => Nil
    }

  def find(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
      info: CallHierarchyItemInfo,
  ): List[FindIncomingCallsResult] = {
    def search(
        tree: Tree,
        parent: Option[Name],
        parentRange: Option[lsp4j.Range],
    ): List[FindIncomingCallsResult] = {
      def default: List[FindIncomingCallsResult] =
        tree.children.flatMap(child => search(child, parent, parentRange))

      def searchVal(
          valRange: lsp4j.Range,
          pat: Pat,
          rhs: Term,
      ): List[FindIncomingCallsResult] =
        extractNameAndPathsFromPat(pat).flatMap { case (name, indices) =>
          traverseTreeWithIndices(rhs, indices.toList)
            .map(tree => search(tree, Some(name), Some(valRange)))
            .getOrElse(default)
        }

      def nameMatchSymbol(name: Name): Boolean =
        definition
          .positionOccurrences(source, name.pos.toLsp.getEnd, doc)
          .exists {
            case ResolvedSymbolOccurrence(
                  _,
                  Some(SymbolOccurrence(_, symbol, role)),
                ) =>
              role.isReference && info.symbols.contains(symbol)
            case _ => false
          }

      tree match {
        case name: Name if !isTypeDeclaration(name) && nameMatchSymbol(name) =>
          (parent, parentRange) match {
            case (Some(parent), Some(parentRange)) =>
              getCallResultFromPosition(
                source,
                doc,
                parent.pos.toLsp.getStart(),
                parentRange,
                name.pos.toLsp,
              )
            case _ =>
              default
          }
        case v: Defn.Val =>
          v.pats match {
            case pat :: _ => searchVal(v.pos.toLsp, pat, v.rhs)
            case Nil => default
          }
        case v: Defn.Var =>
          v.pats match {
            case pat :: _ =>
              v.rhs
                .map(rhs => searchVal(v.pos.toLsp, pat, rhs))
                .getOrElse(default)
            case Nil => default
          }
        case _ => {
          extractNameFromMember(tree) match {
            case Some(RealRoot(member, name)) =>
              tree.children.flatMap(child =>
                search(child, Some(name), Some(member.pos.toLsp))
              )
            case None =>
              default
          }
        }
      }
    }
    search(root, None, None)
  }

  def findSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      info: CallHierarchyItemInfo,
  ): List[FindIncomingCallsResult] = {
    def getCallResultFromRange(range: lsp4j.Range) =
      (for {
        tree <- trees.findLastEnclosingAt(source, range.getStart)
        definition <- findDefinition(tree)
        name = definition.name
      } yield getCallResultFromPosition(
        source,
        doc,
        name.pos.toLsp.getStart,
        name.pos.toLsp,
        range,
        _.role.isDefinition,
      )).getOrElse(Nil)

    val result = doc.synthetics
      .flatMap(syn =>
        extractSelectTree(syn.tree) match {
          case Some(SelectTree(OriginalTree(Some(range)), id))
              if id.exists(id => info.symbols.contains(id.symbol)) =>
            getCallResultFromRange(range.toLsp)
          case _ => Nil
        }
      )
      .toList

    result
  }
}
