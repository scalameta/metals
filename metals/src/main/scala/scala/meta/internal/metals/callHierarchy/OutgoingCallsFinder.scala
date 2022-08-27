package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Member
import scala.meta.Name
import scala.meta.Pat
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.DefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReferenceProvider
import scala.meta.internal.metals.ResolvedSymbolOccurrence
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.IdTree
import scala.meta.internal.semanticdb.OriginalTree
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j
import org.eclipse.lsp4j.Position

class OutgoingCallsFinder(
    semanticdbs: Semanticdbs,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    trees: Trees,
)(implicit ec: ExecutionContext)
    extends CallHierarchyHelpers {

  private case class DefinitionInformation(
      occurence: SymbolOccurrence,
      source: AbsolutePath,
      doc: TextDocument,
  ) {
    def symbolLength: Int = occurence.symbol.length()

    def definitionTree: Option[Tree] = for {
      range <- occurence.range
      definitionTree <- getSpecifiedOrFindDefinition(
        trees.findLastEnclosingAt(source, range.toLSP.getStart())
      )
    } yield definitionTree.root

    def toOutgoingCallResult(
        fromRange: lsp4j.Range
    ): Option[FindOutgoingCallsResult] =
      definitionTree.map(tree =>
        FindOutgoingCallsResult(
          occurence,
          tree.pos.toLSP,
          List(fromRange),
          source,
          doc,
        )
      )

    def isSameDefinition(
        otherDoc: TextDocument,
        root: Option[RealRoot],
    ): Boolean =
      doc == otherDoc && root.exists {
        case NamedRealRoot(rootTree, rootTreeName) =>
          occurence.range.exists(range =>
            !(range.toLSP == rootTreeName.pos.toLSP) && rootTree.pos.encloses(
              range.toLSP
            )
          )
        case _ => false
      }
  }

  private def findDefinitionOccurence(
      source: AbsolutePath,
      symbol: String,
  ): Future[Option[DefinitionInformation]] = {
    def search(source: AbsolutePath) = for {
      doc <- semanticdbs.textDocument(source).documentIncludingStale
      occ <- doc.occurrences.find(occ =>
        occ.symbol == symbol && occ.role.isDefinition && doc.symbols
          .exists(symInfo => symInfo.symbol == symbol)
      )
    } yield DefinitionInformation(occ, source, doc)

    pathsToCheck(references, source, Set(symbol), symbol.isLocal).map(paths =>
      paths.view.flatMap(search).headOption
    )
  }

  private def findCaseClassDefinitionOccurence(
      source: AbsolutePath,
      symbol: String,
  ) = {
    val mightBeCaseClassConstructor = """\.apply\(\)\.$""".r
    if (mightBeCaseClassConstructor.findFirstIn(symbol).isDefined)
      findDefinitionOccurence(
        source,
        mightBeCaseClassConstructor
          .replaceAllIn(
            symbol,
            "#`<init>`().",
          ), // For case class constructor
      )
    else Future.successful(None)
  }

  def find(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  ): Future[List[
    FindOutgoingCallsResult
  ]] = {
    val realRoot = findRealRoot(root)

    def search(
        tree: Tree
    ): Future[
      List[FindOutgoingCallsResult]
    ] = {
      def getDefinitionInformationFromPosition(
          pos: Position
      ): Future[Option[DefinitionInformation]] = {
        val potentialsDefinition = Future.sequence(
          definition.positionOccurrences(source, pos, doc).map {
            case ResolvedSymbolOccurrence(_, Some(occurence)) =>
              findDefinitionOccurence(source, occurence.symbol)
            case _ => Future.successful(None)
          }
        )
        potentialsDefinition.map(_.flatten.maxByOption(_.symbolLength))
      }

      tree match {
        case name: Name if !isTypeDeclaration(name) =>
          for {
            mayDefinitionInfo <- getDefinitionInformationFromPosition(
              name.pos.toLSP.getEnd()
            )
          } yield mayDefinitionInfo
            .filterNot(_.isSameDefinition(doc, realRoot))
            .flatMap(_.toOutgoingCallResult(name.pos.toLSP))
            .toList
        case t
            if extractNameFromMember(t).isDefined || t.is[Term.Param] || t
              .is[Pat.Var] =>
          Future.successful(Nil)
        case other =>
          Future.sequence(other.children.map(search)).map(_.flatten)
      }
    }

    def memberSearch(member: Member) = {
      val nameDefinitionIndex = member.children.indexOf(member.name)
      (if (nameDefinitionIndex == -1) member.children
       else member.children.patch(nameDefinitionIndex, Nil, 1))
        .map(search)
    }

    realRoot match {
      case Some(realRoot: RealRoot) =>
        val definition = realRoot.root
        Future
          .sequence(
            (definition match {
              case member: Member =>
                memberSearch(member)
              case other => List(search(other))
            })
          )
          .map(results => FindCallsResult.group(results.flatten))
      case None => Future.successful(Nil)
    }
  }

  def isDefinedIn(
      source: AbsolutePath,
      definition: Tree,
      range: lsp4j.Range,
  ): Boolean =
    definition.pos.encloses(range) && getSpecifiedOrFindDefinition(
      trees.findLastEnclosingAt(source, range.getStart),
      Some(definition),
    ).exists(_.root == definition)

  def findSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  )(implicit ec: ExecutionContext): Future[List[
    FindOutgoingCallsResult
  ]] = {
    def getOutgoingCallResultFromSymbol(
        symbol: String,
        range: lsp4j.Range,
        mayRealRoot: Option[RealRoot],
    ) = {
      for {
        mayDefinitionInfo <- findDefinitionOccurence(source, symbol)
          .flatMap {
            case opt @ Some(_) => Future.successful(opt)
            case _ => findCaseClassDefinitionOccurence(source, symbol)
          }
        if !mayDefinitionInfo.exists(_.isSameDefinition(doc, mayRealRoot))
      } yield mayDefinitionInfo.flatMap(_.toOutgoingCallResult(range)).toList
    }

    findRealRoot(root) match {
      case mayRealRoot @ Some(realRoot) =>
        val definition = realRoot.root
        val results = doc.synthetics
          .flatMap(syn =>
            extractSelectTree(syn.tree).collect {
              case SelectTree(
                    OriginalTree(Some(range)),
                    Some(IdTree(symbol)),
                  ) if isDefinedIn(source, definition, range.toLSP) =>
                getOutgoingCallResultFromSymbol(
                  symbol,
                  range.toLSP,
                  mayRealRoot,
                )
              case _ => Future.successful(Nil)
            }
          )
          .toList

        Future
          .sequence(results)
          .map(results => FindCallsResult.group(results.flatten))
      case _ => Future.successful(Nil)
    }
  }
}
