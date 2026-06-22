package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.mbt.MbtReferenceProvider
import scala.meta.internal.parsing.EnclosingMethod
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import com.google.gson.JsonElement
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    definitionProvider: DefinitionProvider,
    icons: Icons,
    compilers: () => Compilers,
    trees: Trees,
    buffers: Buffers,
    buildTargets: BuildTargets,
    mbtReferenceProvider: MbtReferenceProvider,
    workDoneProgress: WorkDoneProgress,
) {
  private val javaTrees = new JavaTrees(buffers)
  private val callHierarchyItemBuilder =
    new CallHierarchyItemBuilder(workspace, icons, compilers, buildTargets)

  /**
   * Prepare call hierarchy request by returning a call hierarchy item, resolved for the given text document position.
   */
  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = workDoneProgress.trackFuture(
    "Preparing call hierarchy", {

      def resolvedSymbolOccurrence2CallHierarchyItem(
          occurrence: SymbolOccurrence,
          source: AbsolutePath,
      ): Option[Future[CallHierarchyItem]] = {
        val isMethodOrType =
          occurrence.symbol.endsWith(").") || occurrence.symbol.endsWith("#")
        if (isMethodOrType) {
          def build(range: l.Range): Future[CallHierarchyItem] = {
            callHierarchyItemBuilder.build(
              source,
              occurrence.symbol,
              range,
              Array.empty,
              token,
              l.SymbolKind.Method,
            )
          }
          if (occurrence.role.isDefinition) {
            Some(build(occurrence.range.get.toLsp))
          } else {
            occurrence.range.map { range =>
              val textDocumentParams = new l.TextDocumentPositionParams(
                new l.TextDocumentIdentifier(source.toURI.toString),
                range.toLsp.getStart,
              )
              definitionProvider
                .definition(source, textDocumentParams, token)
                .flatMap { defResult =>
                  defResult.locations.asScala.headOption match {
                    case Some(defLocation) =>
                      build(defLocation.getRange)
                    case None =>
                      build(range.toLsp)
                  }
                }
            }
          }
        } else None
      }

      val source = params.getTextDocument.getUri.toAbsolutePath
      val allResults = mbtReferenceProvider
        .enclosingOccurrences(params.getPosition, source)
        .flatMap { occurrence =>
          resolvedSymbolOccurrence2CallHierarchyItem(occurrence, source)
        }
      Future.sequence(allResults.toList)
    },
  )

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
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyIncomingCall]] =
    workDoneProgress.trackProgressFuture(
      "Resolving incoming calls",
      { taskProgress =>
        val uri = new TextDocumentIdentifier(params.getItem.getUri)
        val info = getInfo(params.getItem().getData())
        val allReferences = mbtReferenceProvider.references(
          new ReferenceParams(
            uri,
            params.getItem.getSelectionRange.getStart(),
            new ReferenceContext(false),
          )
        )
        val allCallHierarchyItems = allReferences.flatMap { allReferences =>
          val allDefns = for {
            reference <- allReferences
            location <- reference.locations
            call <- containingCall(
              location.getRange(),
              location.getUri().toAbsolutePath,
            )
          } yield { (reference.symbol, call, location) }
          val deduplicated = allDefns.groupBy { case (_, call, _) =>
            call
          }
          val all = deduplicated.toList.flatMap {
            case (enclosingMethod, calls) => {
              val ranges = calls.map { case (_, _, location) =>
                location.getRange
              }.distinct
              mbtReferenceProvider
                .enclosingOccurrences(
                  enclosingMethod.nameRange.getStart(),
                  enclosingMethod.source,
                )
                .map(_.symbol)
                .filterNot(info.visited.contains)
                .headOption
                .map { symbol =>
                  callHierarchyItemBuilder
                    .build(
                      enclosingMethod.source,
                      symbol,
                      enclosingMethod.nameRange,
                      info.visited :+ symbol,
                      token,
                      l.SymbolKind.Method,
                    )
                    .map { item =>
                      new CallHierarchyIncomingCall(item, ranges.asJava)
                    }
                }
            }
          }
          Future.sequence(all)
        }
        allCallHierarchyItems
      },
    )

  def containingCall(
      range: l.Range,
      source: AbsolutePath,
  ): Option[EnclosingMethod] = {
    if (source.isScalaFilename) {
      trees
        .findLastEnclosingAt[Defn.Def](source, range.getStart)
        .map(defn =>
          EnclosingMethod(defn.name.pos.toLsp, defn.pos.toLsp, source)
        )
    } else if (source.isJavaFilename) {
      javaTrees.findEnclosingJavaMethod(source, range.getStart)
    } else None
  }

  private def isValidLocation(location: l.Location): Boolean = {
    location.getRange.getStart.getLine >= 0 &&
    location.getRange.getStart.getCharacter >= 0 &&
    location.getRange.getEnd.getLine >= 0 &&
    location.getRange.getEnd.getCharacter >= 0
  }

  /**
   * Resolve outgoing calls for a given call hierarchy item.
   * Uses MBT to find all method calls within the method body.
   */
  def outgoingCalls(
      params: CallHierarchyOutgoingCallsParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[List[CallHierarchyOutgoingCall]] =
    workDoneProgress.trackProgressFuture(
      "Resolving outgoing calls",
      { _ =>
        val source = params.getItem.getUri.toAbsolutePath
        val info = getInfo(params.getItem.getData)

        val outgoingCallFutures = for {
          enclosingMethod <- containingCall(
            params.getItem.getSelectionRange(),
            source,
          ).toList
          methodReferences =
            mbtReferenceProvider.methodReferencesInRange(
              source,
              enclosingMethod.bodyRange,
            )
          groupedBySymbol =
            methodReferences.groupBy { case (occ, _) => occ.symbol }
          (symbol, occurrencesWithDefs) <- groupedBySymbol.toList
          if !info.visited.contains(symbol)
          localDef = occurrencesWithDefs
            .flatMap { case (_, defs) => defs.headOption }
            .headOption
            // default record classes constructors have that at -1 -1
            .filter(isValidLocation(_))
          firstRangeOccurrence <- occurrencesWithDefs
            .flatMap(_._1.range)
            .headOption
          methodDefLocation = localDef match {
            case Some(defLocation) => Future.successful(Some(defLocation))
            case None =>
              val textDocumentParams = new l.TextDocumentPositionParams(
                new l.TextDocumentIdentifier(source.toURI.toString),
                firstRangeOccurrence.toLsp.getStart,
              )
              definitionProvider
                .definition(source, textDocumentParams, EmptyCancelToken)
                .map(_.locations.asScala.collectFirst {
                  case loc if isValidLocation(loc) => loc
                })
          }
        } yield {
          val callRanges = occurrencesWithDefs.flatMap { case (occ, _) =>
            occ.range.map(_.toLsp)
          }.toList
          methodDefLocation.map { methodDefLocation =>
            methodDefLocation.map { defLocation =>
              val symbolKind =
                if (symbol.contains("<init>")) l.SymbolKind.Constructor
                else l.SymbolKind.Method
              callHierarchyItemBuilder
                .build(
                  defLocation.getUri().toAbsolutePath,
                  symbol,
                  defLocation.getRange,
                  info.visited :+ symbol,
                  token,
                  symbolKind,
                )
                .map { item =>
                  new CallHierarchyOutgoingCall(item, callRanges.asJava)
                }
            }
          }
        }

        Future
          .sequence(outgoingCallFutures)
          .map(_.flatten)
          .flatMap { futures =>
            Future.sequence(futures)
          }
      },
    )
}
