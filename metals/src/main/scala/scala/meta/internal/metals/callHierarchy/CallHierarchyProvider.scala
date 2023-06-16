package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Tree
import scala.meta.internal.implementation.Supermethods
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import com.google.gson.JsonElement
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    definition: DefinitionProvider,
    references: ReferenceProvider,
    icons: Icons,
    compilers: () => Compilers,
    trees: Trees,
    buildTargets: BuildTargets,
    supermethods: Supermethods,
)(implicit ec: ExecutionContext)
    extends CallHierarchyHelpers {

  private val callHierarchyItemBuilder =
    new CallHierarchyItemBuilder(workspace, icons, compilers, buildTargets)

  private val incomingCallsFinder = new IncomingCallsFinder(definition, trees)
  private val outgoingCallsFinder =
    new OutgoingCallsFinder(semanticdbs, definition, references, trees)

  /**
   * Prepare call hierarchy request by returning a call hierarchy item, resolved for the given text document position.
   */
  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = {

    def resolvedSymbolOccurence2CallHierarchyItem(
        rso: ResolvedSymbolOccurrence,
        source: AbsolutePath,
        doc: TextDocument,
    ): Future[Option[CallHierarchyItem]] = {
      val result = for {
        occurence <- rso.occurrence
        if occurence.role.isDefinition
        range <- occurence.range
        tree <- trees.findLastEnclosingAt(source, range.toLsp.getStart)
        definition <- findDefinition(tree)
      } yield callHierarchyItemBuilder.build(
        source,
        doc,
        occurence,
        definition.root.pos.toLsp,
        Array(occurence.symbol),
        token,
      )
      result
        .getOrElse(Future.successful(None))
    }

    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val results =
          definition.positionOccurrences(source, params.getPosition, doc)
        Future
          .sequence(
            results.map(result =>
              resolvedSymbolOccurence2CallHierarchyItem(result, source, doc)
            )
          )
          .map(_.flatten)
      case None =>
        Future.successful(Nil)
    }
  }

  /**
   * The data entry field that is preserved between a call hierarchy prepare and
   * incoming calls or outgoing calls requests have a unknown type (Object in Java)
   */
  private def getInfo(data: Object): CallHierarchyItemInfo = data
    .asInstanceOf[JsonElement]
    .as[CallHierarchyItemInfo]
    .get

  /**
   * Resolve incoming calls for a given call hierarchy item.
   */
  def incomingCalls(
      params: CallHierarchyIncomingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyIncomingCall]] = {
    val parents = supermethods
      .getSuperMethodHierarchySymbols(
        params.getItem.getUri,
        params.getItem.getSelectionRange.getStart,
      )
      .getOrElse(List())
    val info =
      getInfo(params.getItem.getData).withVisitedSymbols(parents.toArray)
    def getIncomingCallsInSpecifiedSource(
        source: AbsolutePath
    ): List[Future[CallHierarchyIncomingCall]] = {
      semanticdbs.textDocument(source).documentIncludingStale match {
        case Some(doc) if (!containsDuplicates(info.visited)) =>
          val results = trees
            .get(source)
            .map(root =>
              incomingCallsFinder.find(
                source,
                doc,
                root,
                info,
              ) ++ incomingCallsFinder.findSynthetics(source, doc, info)
            )
            .getOrElse(Nil)

          FindCallsResult
            .group(results)
            .map(
              _.toLsp(
                source,
                doc,
                callHierarchyItemBuilder,
                info.visited,
                token,
              )
            )
        case _ =>
          Nil
      }
    }

    pathsToCheck(
      references,
      params.getItem().getUri().toAbsolutePath,
      info.symbols.toSet,
      info.isLocal,
      info.searchLocal,
    )
      .flatMap(paths =>
        Future.sequence(paths.flatMap(getIncomingCallsInSpecifiedSource))
      )
  }

  /**
   * Resolve outgoing calls for a given call hierarchy item.
   */
  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyOutgoingCall]] = {
    val source = params.getItem.getUri.toAbsolutePath

    val info = getInfo(params.getItem.getData)

    def searchOutgoingCalls(
        doc: TextDocument,
        root: Tree,
    ): Future[List[CallHierarchyOutgoingCall]] = {
      for {
        calls <- outgoingCallsFinder.find(
          source,
          doc,
          root,
        )
        callsSynthetics <- outgoingCallsFinder.findSynthetics(
          source,
          doc,
          root,
        )
        results <- Future.sequence(
          FindCallsResult
            .group(calls ++ callsSynthetics)
            .map(
              _.toLsp(callHierarchyItemBuilder, info.visited, token)
            )
        )
      } yield results
    }

    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) if (!containsDuplicates(info.visited)) =>
        trees
          .findLastEnclosingAt(
            source,
            params.getItem.getSelectionRange.getStart,
          )
          .map(root => searchOutgoingCalls(doc, root))
          .getOrElse(Future.successful(Nil))
      case _ =>
        Future.successful(Nil)
    }
  }
}
