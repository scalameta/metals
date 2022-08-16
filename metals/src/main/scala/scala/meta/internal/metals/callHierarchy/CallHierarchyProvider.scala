package scala.meta.internal.metals.callHierarchy

import scala.meta.internal.metals._
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.{semanticdb => s}

import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer

import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.{lsp4j => l}
import scala.meta.Tree
import scala.meta.Pat
import scala.meta.Name
import com.google.gson.JsonElement
import scala.meta.Term
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.meta.Defn
import scala.meta.Member

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    icons: Icons,
    compilers: () => Compilers,
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets,
) extends SemanticdbFeatureProvider
    with CallHierarchyHelpers {

  override def reset(): Unit = ()

  override def onDelete(file: AbsolutePath): Unit = ()

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = ()

  /**
   * Data that is preserved between a call hierarchy prepare and incoming calls or outgoing calls request.
   *
   * @param symbols The set of symbols concerned by the request.
   * @param visited Symbols that are already visited to handle recursive cases.
   */
  private case class CallHierarchyItemInfo(
      symbols: Array[String],
      visited: Array[String],
  )

  private def buildCallHierarchyItem(
      source: AbsolutePath,
      doc: TextDocument,
      occurence: SymbolOccurrence,
      range: l.Range,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Option[CallHierarchyItem]] = {
    occurence match {
      case SymbolOccurrence(Some(itemRange), symbol, _) =>
        compilers()
          .hover(
            new HoverExtParams(
              source.toTextDocumentIdentifier,
              null,
              itemRange.toLSP,
            ),
            token,
          )
          .map(hover =>
            doc.symbols
              .find(_.symbol == symbol)
              .map(info => {
                val item = new CallHierarchyItem(
                  info.displayName,
                  info.kind.toLSP,
                  source.toURI.toString,
                  range,
                  itemRange.toLSP,
                )

                val symbols = (Set(info.symbol) union (
                  if (info.isClass)
                    info.signature
                      .asInstanceOf[s.ClassSignature]
                      .declarations
                      .flatMap(
                        _.symlinks.find(_.endsWith("`<init>`()."))
                      ) // Class constructors can be included in the symbols to look at.
                      .toSet
                  else Set.empty
                )).toArray

                val displayName =
                  if (
                    info.isConstructor && info.displayName == "<init>"
                  ) // Get the name of the class for constructors
                    info.symbol.slice(
                      info.symbol.lastIndexOf("/") + 1,
                      info.symbol.lastIndexOf("#"),
                    )
                  else info.displayName

                item.setName(displayName)

                item.setDetail(
                  (if (visited.dropRight(1).contains(symbol))
                     icons.sync + " "
                   else "") + getSignatureFromHover(hover).getOrElse("")
                )

                item.setData(CallHierarchyItemInfo(symbols, visited))

                item
              })
          )
      case _ => Future.successful(None)
    }
  }

  /**
   * Prepare call hierarchy request by returning a call hierarchy item, resolved for the given text document position.
   */
  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results: List[ResolvedSymbolOccurrence] =
          definition.positionOccurrences(source, params.getPosition, doc)
        Future
          .sequence(results.flatMap { result =>
            for {
              occurence <- result.occurrence
              if occurence.role.isDefinition
              range <- occurence.range
              tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
              (definition, _) <- getSpecifiedOrFindDefinition(Some(tree))
            } yield buildCallHierarchyItem(
              source,
              doc,
              occurence,
              definition.pos.toLSP,
              Array(occurence.symbol),
              token,
            )
          })
          .map(_.flatten)
      case None =>
        Future.successful(Nil)
    }
  }

  private def findIncomingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
      info: CallHierarchyItemInfo,
  ): List[FindIncomingCallsResult] = {
    def search(
        tree: Tree,
        parent: Option[Name],
        parentRange: Option[l.Range],
    ): List[FindIncomingCallsResult] = {
      def default =
        tree.children.flatMap(child => search(child, parent, parentRange))

      def searchVal(valRange: l.Range, pat: Pat, rhs: Term) =
        extractNameAndPathsFromPat(pat).flatMap { case (name, indices) =>
          traverseTreeWithIndices(rhs, indices.toList)
            .map(tree => search(tree, Some(name), Some(valRange)))
            .getOrElse(default)
        }

      tree match {
        case name: Name
            if !isTypeDeclaration(name) && definition
              .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
              .exists(
                _.occurrence.exists(occ =>
                  occ.role.isReference && info.symbols.contains(occ.symbol)
                )
              ) =>
          parent match {
            case Some(parent) =>
              definition
                .positionOccurrences(source, parent.pos.toLSP.getStart, doc)
                .collect { case ResolvedSymbolOccurrence(_, Some(occurence)) =>
                  FindIncomingCallsResult(
                    occurence,
                    parentRange.get,
                    List(name.pos.toLSP),
                  )
                }
            case _ =>
              name.children.flatMap { child =>
                search(child, parent, parentRange)
              }
          }
        case v: Defn.Val =>
          v.pats match {
            case pat :: _ => searchVal(v.pos.toLSP, pat, v.rhs)
            case Nil => default
          }
        case v: Defn.Var =>
          v.pats match {
            case pat :: _ =>
              v.rhs
                .map(rhs => searchVal(v.pos.toLSP, pat, rhs))
                .getOrElse(default)
            case Nil => default
          }
        case _ => {
          extractNameFromMember(tree) match {
            case Some((member, name)) =>
              tree.children.flatMap(child =>
                search(child, Some(name), Some(member.pos.toLSP))
              )
            case None =>
              default
          }
        }
      }
    }
    FindIncomingCallsResult.group(search(root, None, None))
  }

  private def findIncomingCallsSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      info: CallHierarchyItemInfo,
  ): List[FindIncomingCallsResult] =
    FindIncomingCallsResult.group(
      doc.synthetics
        .flatMap(syn =>
          extractSelectTree(syn.tree).collect {
            case s.SelectTree(s.OriginalTree(Some(range)), id)
                if id.exists(id => info.symbols.contains(id.symbol)) => (
              for {
                tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
                (definition, name) <- getSpecifiedOrFindDefinition(Some(tree))
                occurence <- this.definition
                  .positionOccurrence(source, name.pos.toLSP.getStart, doc)
                  .occurrence
                  .filter(_.role.isDefinition)
              } yield FindIncomingCallsResult(
                occurence,
                name.pos.toLSP,
                List(range.toLSP),
              )
            )
          }
        )
        .flatten
        .toList
    )

  /**
   * Resolve incoming calls for a given call hierarchy item.
   */
  def incomingCalls(
      params: CallHierarchyIncomingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyIncomingCall]] = {
    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    for {
      paths <- references
        .pathsMightContainSymbol(
          params.getItem.getUri.toAbsolutePath,
          info.symbols.toSet,
        )
      result <- Future.sequence(
        paths.toList
          .map(source => {
            semanticdbs.textDocument(source).documentIncludingStale match {
              case Some(doc) =>
                val results = trees
                  .get(source)
                  .map(root =>
                    findIncomingCalls(
                      source,
                      doc,
                      root,
                      info,
                    ) ++ findIncomingCallsSynthetics(
                      source,
                      doc,
                      info,
                    )
                  )
                  .getOrElse(Nil)

                Future
                  .sequence(results.map {
                    case FindIncomingCallsResult(
                          occurence @ SymbolOccurrence(_, symbol, _),
                          range,
                          ranges,
                        ) if !containsDuplicates(info.visited) =>
                      buildCallHierarchyItem(
                        source,
                        doc,
                        occurence,
                        range,
                        info.visited :+ symbol,
                        token,
                      )
                        .mapOptionInside(chi =>
                          new CallHierarchyIncomingCall(
                            chi,
                            ranges.asJava,
                          )
                        )
                    case _ => Future.successful(None)
                  })
                  .map(_.flatten)
              case None =>
                Future.successful(Nil)
            }
          })
      )
    } yield (result.flatten)
  }

  private def findDefinitionOccurence(
      source: AbsolutePath,
      symbol: String,
  )(implicit
      ec: ExecutionContext
  ): Future[Option[(SymbolOccurrence, AbsolutePath, TextDocument)]] =
    references
      .pathsMightContainSymbol(source, Set(symbol))
      .map(paths =>
        paths.view
          .map(source =>
            for {
              doc <- semanticdbs.textDocument(source).documentIncludingStale
              occ <- doc.occurrences.find(occ =>
                occ.symbol == symbol && occ.role.isDefinition && doc.symbols
                  .exists(symInfo => symInfo.symbol == symbol)
              )
            } yield (occ, source, doc)
          )
          .find(_.isDefined)
          .flatten
      )

  private def findOutgoingCalls(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  )(implicit ec: ExecutionContext): Future[List[
    FindOutgoingCallsResult
  ]] = {
    val realRoot = findRealRoot(root)

    def search(
        tree: Tree
    ): Future[
      List[FindOutgoingCallsResult]
    ] =
      tree match {
        case name: Name if !isTypeDeclaration(name) =>
          (for {
            definitionInfo <- Future
              .sequence(
                definition
                  .positionOccurrences(source, name.pos.toLSP.getEnd, doc)
                  .flatMap(rso =>
                    rso.occurrence
                      .map(occ => findDefinitionOccurence(source, occ.symbol))
                  )
              )
              .map(
                _.flatten
                  .sortBy(
                    _._1.symbol.length * -1
                  )
                  .headOption // The most specific occurrence is the longest
              )
            result = for {
              (definitionOccurence, definitionSource, definitionDoc) <-
                definitionInfo
              definitionRange <- definitionOccurence.range
              if !(definitionDoc == doc && realRoot.exists {
                case (definition, name) =>
                  !(name.pos.toLSP == definitionRange.toLSP) && definition.pos
                    .encloses(definitionRange.toLSP)
              })
              definitionName <- trees.findLastEnclosingAt(
                definitionSource,
                definitionRange.toLSP.getStart,
              )
              (definition, _) <- getSpecifiedOrFindDefinition(
                Some(definitionName)
              )
            } yield FindOutgoingCallsResult(
              definitionOccurence,
              definition.pos.toLSP,
              List(name.pos.toLSP),
              definitionSource,
              definitionDoc,
            )

          } yield result).map(_.toList)
        case t
            if extractNameFromMember(t).isDefined || t.is[Term.Param] || t
              .is[Pat.Var] =>
          Future.successful(Nil)
        case other =>
          Future.sequence(other.children.map(search)).map(_.flatten)
      }

    realRoot match {
      case Some((definition, _)) =>
        Future
          .sequence(
            (definition match {
              case member: Member =>
                val nameDefinitionIndex = member.children.indexOf(member.name)
                (if (nameDefinitionIndex == -1) member.children
                 else member.children.patch(nameDefinitionIndex, Nil, 1))
                  .map(search)
              case other => List(search(other))
            })
          )
          .map(results => FindOutgoingCallsResult.group(results.flatten))
      case None => Future.successful(Nil)
    }
  }

  private def findOutgoingCallsSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      root: Tree,
  )(implicit ec: ExecutionContext): Future[List[
    FindOutgoingCallsResult
  ]] =
    (findRealRoot(root) match {
      case Some((definition, _)) =>
        val definitionRange = definition.pos.toSemanticdb
        Future
          .sequence(
            doc.synthetics
              .flatMap(syn =>
                extractSelectTree(syn.tree).collect {
                  case s.SelectTree(
                        s.OriginalTree(Some(range)),
                        Some(s.IdTree(symbol)),
                      )
                      if definitionRange
                        .encloses(range) && getSpecifiedOrFindDefinition(
                        trees.findLastEnclosingAt(source, range.toLSP.getStart),
                        Some(definition),
                      ).exists(_._1 == definition) =>
                    lazy val mightBeCaseClassConstructor =
                      """\.apply\(\)\.$""".r

                    for {
                      definitionInfo <- findDefinitionOccurence(source, symbol)
                        .flatMap {
                          case opt @ Some(_) => Future.successful(opt)
                          case _
                              if mightBeCaseClassConstructor
                                .findFirstIn(symbol)
                                .isDefined =>
                            findDefinitionOccurence(
                              source,
                              mightBeCaseClassConstructor
                                .replaceAllIn(
                                  symbol,
                                  "#`<init>`().",
                                ), // For case class constructor
                            )
                          case _ => Future.successful(None)
                        }

                    } yield (definitionInfo.flatMap {
                      case (
                            definitionOccurence,
                            definitionSource,
                            definitionDoc,
                          ) =>
                        for {
                          definitionRange <- definitionOccurence.range
                          if !(definitionDoc == doc && definition.pos
                            .encloses(definitionRange.toLSP))
                          definitionName <- trees.findLastEnclosingAt(
                            definitionSource,
                            definitionRange.toLSP.getStart,
                          )
                          (definition, _) <- getSpecifiedOrFindDefinition(
                            Some(definitionName)
                          )
                        } yield FindOutgoingCallsResult(
                          definitionOccurence,
                          definition.pos.toLSP,
                          List(range.toLSP),
                          definitionSource,
                          definitionDoc,
                        )
                    })
                  case _ => Future.successful(Nil)
                }
              )
              .toList
          )
          .map(_.flatten)
      case None => Future.successful(Nil)
    }).map(results => FindOutgoingCallsResult.group(results))

  /**
   * Resolve outgoing calls for a given call hierarchy item.
   */
  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyOutgoingCall]] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = params.getItem.getData
      .asInstanceOf[JsonElement]
      .as[CallHierarchyItemInfo]
      .get

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        trees
          .findLastEnclosingAt(
            source,
            params.getItem.getSelectionRange.getStart,
          )
          .map(root =>
            for {
              calls <- findOutgoingCalls(
                source,
                doc,
                root,
              )
              callsSynthetics <- findOutgoingCallsSynthetics(
                source,
                doc,
                root,
              )
            } yield calls ++ callsSynthetics
          )
          .getOrElse(Future.successful(Nil))
          .flatMap(results =>
            Future
              .sequence(results.map {
                case FindOutgoingCallsResult(
                      occurence @ SymbolOccurrence(_, symbol, _),
                      range,
                      ranges,
                      definitionSource,
                      definitionDoc,
                    ) if !containsDuplicates(info.visited) =>
                  buildCallHierarchyItem(
                    definitionSource,
                    definitionDoc,
                    occurence,
                    range,
                    info.visited :+ symbol,
                    token,
                  ).mapOptionInside(item =>
                    new CallHierarchyOutgoingCall(
                      item,
                      ranges.asJava,
                    )
                  )
                case _ => Future.successful(None)
              })
              .map(_.flatten)
          )
      case None =>
        Future.successful(Nil)
    }
  }
}
