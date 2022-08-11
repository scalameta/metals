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
  import CanBuildCallHierarchyItem._

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

  private def buildCallHierarchyItemFrom(
      source: AbsolutePath,
      doc: TextDocument,
      canBuildHierarchyItem: CanBuildCallHierarchyItem[_],
      range: l.Range,
      visited: Array[String],
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Option[CallHierarchyItem]] = {
    canBuildHierarchyItem match {
      case CanBuildCallHierarchyItem(symbol, itemRange) =>
        compilers()
          .hover(
            new HoverExtParams(
              source.toTextDocumentIdentifier,
              null,
              itemRange,
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
                  itemRange,
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
              (definition, _) <- findDefinition(Some(tree))
            } yield buildCallHierarchyItemFrom(
              source,
              doc,
              SymbolOccurenceCHI(occurence),
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
  ): List[FindIncomingCallsResult[SymbolOccurrence]] = {
    def search(
        tree: Tree,
        parent: Option[Name],
        parentRange: Option[l.Range],
    ): List[FindIncomingCallsResult[SymbolOccurrence]] = tree match {
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
                  SymbolOccurenceCHI(occurence),
                  parentRange.get,
                  List(name.pos.toLSP),
                )
              }
          case _ =>
            name.children.flatMap { child =>
              search(child, parent, parentRange)
            }
        }
      case _ => {
        extractNameFromDefinition(tree) match {
          case Some((definition, name)) =>
            tree.children.flatMap(child =>
              search(child, Some(name), Some(definition.pos.toLSP))
            )
          case None =>
            tree.children.flatMap(child => search(child, parent, parentRange))
        }
      }
    }

    FindIncomingCallsResult.group(search(root, None, None))
  }

  private def findIncomingCallsSynthetics(
      source: AbsolutePath,
      doc: TextDocument,
      info: CallHierarchyItemInfo,
  ): List[FindIncomingCallsResult[s.SelectTree]] =
    FindIncomingCallsResult.group(
      doc.synthetics
        .flatMap(syn =>
          extractSelectTree(syn.tree).collect {
            case st @ s.SelectTree(s.OriginalTree(Some(range)), id)
                if id.exists(id => info.symbols.contains(id.symbol)) => (
              for {
                tree <- trees.findLastEnclosingAt(source, range.toLSP.getStart)
                (definition, name) <- findDefinition(Some(tree))
              } yield FindIncomingCallsResult(
                SelectTreeCHI(st),
                definition.pos.toLSP,
                List(name.pos.toLSP),
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
                          item @ CanBuildCallHierarchyItem(symbol, _),
                          range,
                          ranges,
                        ) if !containsDuplicates(info.visited) =>
                      buildCallHierarchyItemFrom(
                        source,
                        doc,
                        item,
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
    FindOutgoingCallsResult[SymbolOccurrence]
  ]] = {

    val realRoot = findDefinition(Some(root))

    def search(
        tree: Tree
    ): Future[
      List[FindOutgoingCallsResult[SymbolOccurrence]]
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
              if !(definitionDoc == doc && realRoot.exists(
                _._1.pos.encloses(definitionRange.toLSP)
              ))
              definitionName <- trees.findLastEnclosingAt(
                definitionSource,
                definitionRange.toLSP.getStart,
              )
              (definition, _) <- findDefinition(Some(definitionName))
            } yield FindOutgoingCallsResult(
              SymbolOccurenceCHI(definitionOccurence),
              definition.pos.toLSP,
              List(name.pos.toLSP),
              definitionSource,
              definitionDoc,
            )

          } yield result).map(_.toList)
        case t
            if extractNameFromDefinition(t).isDefined || t.is[Term.Param] || t
              .is[Pat.Var] =>
          Future.successful(Nil)
        case other =>
          Future.sequence(other.children.map(search)).map(_.flatten)
      }

    realRoot match {
      case Some((definition, _)) =>
        Future
          .sequence(
            definition.children.collect {
              case t if t.isNot[Name] => search(t)
            }
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
    FindOutgoingCallsResult[SymbolOccurrence]
  ]] =
    (findDefinition(Some(root)) match {
      case Some((definition, _)) =>
        val defintionRange = definition.pos.toSemanticdb
        Future
          .sequence(
            doc.synthetics
              .flatMap(syn =>
                extractSelectTree(syn.tree).collect {
                  case s.SelectTree(
                        s.OriginalTree(Some(range)),
                        Some(s.IdTree(symbol)),
                      )
                      if defintionRange.encloses(range) && findDefinition(
                        trees.findLastEnclosingAt(source, range.toLSP.getStart)
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
                          (definition, _) <- findDefinition(
                            Some(definitionName)
                          )
                        } yield FindOutgoingCallsResult(
                          SymbolOccurenceCHI(definitionOccurence),
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
                      item @ CanBuildCallHierarchyItem(symbol, _),
                      range,
                      ranges,
                      definitionSource,
                      definitionDoc,
                    ) if !containsDuplicates(info.visited) =>
                  buildCallHierarchyItemFrom(
                    definitionSource,
                    definitionDoc,
                    item,
                    range,
                    info.visited :+ symbol,
                    token,
                  ).mapOptionInside(chi =>
                    new CallHierarchyOutgoingCall(
                      chi,
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
