package scala.meta.internal.metals.callHierarchy

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.internal.metals._
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtReferenceProvider
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
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.{lsp4j => l}

final case class CallHierarchyProvider(
    workspace: AbsolutePath,
    definition: DefinitionProvider,
    icons: Icons,
    compilers: () => Compilers,
    trees: Trees,
    buildTargets: BuildTargets,
    mbtReferenceProvider: MbtReferenceProvider,
    workDoneProgress: WorkDoneProgress,
){

  private val callHierarchyItemBuilder =
    new CallHierarchyItemBuilder(workspace, icons, compilers, buildTargets)

  /**
   * Prepare call hierarchy request by returning a call hierarchy item, resolved for the given text document position.
   */
  def prepare(params: CallHierarchyPrepareParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[List[CallHierarchyItem]] = workDoneProgress.trackFuture(
    "Preparing call hierarchy", {

      def resolvedSymbolOccurence2CallHierarchyItem(
          occurrence: SymbolOccurrence,
          source: AbsolutePath,
      ): Option[Future[CallHierarchyItem]] = {
        val isMethod = occurrence.symbol.endsWith(").")
        if (occurrence.role.isDefinition && isMethod) {
          Some(
            callHierarchyItemBuilder.build(
              source,
              occurrence.symbol,
              occurrence.range.get.toLsp,
              Array(occurrence.symbol),
              token,
              l.SymbolKind.Method,
            )
          )
        } else None
      }

      val source = params.getTextDocument.getUri.toAbsolutePath
      val allResults = mbtReferenceProvider
        .enclosingOccurrences(params.getPosition, source)
        .flatMap { occurrence =>
          resolvedSymbolOccurence2CallHierarchyItem(occurrence, source)
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
        val source = uri.getUri.toAbsolutePath
        val allReferences = mbtReferenceProvider.references(
          new ReferenceParams(
            uri,
            params.getItem.getSelectionRange.getStart(),
            new ReferenceContext(false),
          )
        )
        val allCallHierarchyItems = allReferences.flatMap { refs =>
          val allDefns = for {
            path <- refs
            location <- path.locations
            call <- containingCall(location)
          } yield { (path.symbol, call, location) }
          val deduplicated = allDefns.groupBy { case (_, call, _) =>
            call
          }
          val all = deduplicated.toList.map {
            case (defn, calls) => {
              val ranges = calls.map { case (_, _, location) =>
                location.getRange
              }
              val symbol = mbtReferenceProvider
                .enclosingOccurrences(defn.name.pos.toLsp.getStart(), source)
                .map(_.symbol)
                .head // TODO, not head

              callHierarchyItemBuilder
                .build(
                  source,
                  symbol,
                  defn.name.pos.toLsp,
                  Array(symbol),
                  token,
                  l.SymbolKind.Method,
                )
                .map { item =>
                  new CallHierarchyIncomingCall(item, ranges.asJava)
                }

            }
          }
          Future.sequence(all)
        }
        allCallHierarchyItems
      },
    )

  def containingCall(
      location: Location
  ): Option[Defn.Def] = {
    if (location.getUri().isScala) {
      val source = location.getUri.toAbsolutePath
      trees.findLastEnclosingAt[Defn.Def](source, location.getRange.getStart)
    } else
      None // Else find pos enclosing to the location, we can generalize method to return just name position
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

        trees
          .findLastEnclosingAt[Defn.Def](
            source,
            params.getItem.getSelectionRange.getStart,
          )
          .map { root =>
            val methodReferences =
              mbtReferenceProvider.methodReferencesInRange(
                source,
                root.pos.toLsp,
              )
            pprint.log(methodReferences)

            val groupedBySymbol =
              methodReferences.groupBy { case (occ, _) => occ.symbol }

            val outgoingCallFutures = for {
              (symbol, occurrencesWithDefs) <- groupedBySymbol.toList
              localDef = occurrencesWithDefs.flatMap(_._2).headOption
              defLocation <- localDef
                .orElse {
                  val locations = definition.fromSymbol(symbol, Some(source))
                  if (locations.isEmpty) None else Some(locations.get(0))
                }
              defPath = defLocation.getUri.toAbsolutePath
            } yield {
              val callRanges = occurrencesWithDefs.flatMap { case (occ, _) =>
                occ.range.map(_.toLsp)
              }.toList
              callHierarchyItemBuilder
                .build(
                  defPath,
                  symbol,
                  defLocation.getRange,
                  info.visited :+ symbol,
                  token,
                  l.SymbolKind.Method,
                )
                .map { item =>
                  new CallHierarchyOutgoingCall(item, callRanges.asJava)
                }
            }

            Future.sequence(outgoingCallFutures)
          }
          .getOrElse(Future.successful(Nil))
      },
    )
}
