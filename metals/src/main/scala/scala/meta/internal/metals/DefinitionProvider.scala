package scala.meta.internal.metals

import java.{util => ju}
import java.util.Collections
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.Location
import scala.meta.pc.CancelToken
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.meta.internal.semanticdb.SymbolOccurrence
import org.eclipse.lsp4j.Position

/**
 * Implements goto definition that works even in code that doesn't parse.
 *
 * Uses token edit-distance to align identifiers in the current open
 * buffer with symbol occurrences from the latest SemanticDB snapshot.
 *
 * The implementation logic for converting positions between the latest
 * SemanticDB snapshot and current open buffer is quite hairy. We need
 * to convert positions in both the "source" (where definition request
 * is made) and the "destination" (location of the symbol definition).
 * This requires using token edit distance twice:
 *
 * - source: dirty buffer -> snapshot
 * - destination: snapshot -> dirty buffer
 */
final class DefinitionProvider(
    workspace: AbsolutePath,
    mtags: Mtags,
    buffers: Buffers,
    index: GlobalSymbolIndex,
    semanticdbs: Semanticdbs,
    icons: Icons,
    statusBar: StatusBar,
    warnings: Warnings,
    compilers: () => Compilers
)(implicit ec: ExecutionContext) {

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[DefinitionResult] = {
    val fromSemanticdb =
      semanticdbs.textDocument(path).documentIncludingStale match {
        case Some(doc) =>
          definitionFromSnapshot(path, params, doc)
        case _ =>
          warnings.noSemanticdb(path)
          DefinitionResult.empty
      }
    if (fromSemanticdb.locations.isEmpty()) {
      compilers().definition(params, token)
    } else {
      Future.successful(fromSemanticdb)
    }
  }

  def fromSymbol(sym: String): ju.List[Location] =
    DefinitionDestination.fromSymbol(sym).flatMap(_.toResult) match {
      case None => ju.Collections.emptyList()
      case Some(destination) => destination.locations
    }

  def symbolOccurence(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams
  ): Option[(SymbolOccurrence, TextDocument)] = {
    for {
      currentDocument <- semanticdbs
        .textDocument(source)
        .documentIncludingStale
      posOcc = positionOccurrence(
        source,
        dirtyPosition,
        currentDocument
      )
      symbolOccurrence <- {
        def mtagsOccurrence =
          fromMtags(source, dirtyPosition.getPosition())
        posOcc.occurrence.orElse(mtagsOccurrence)
      }
    } yield (symbolOccurrence, currentDocument)
  }

  def positionOccurrence(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams,
      snapshot: TextDocument
  ): ResolvedSymbolOccurrence = {
    // Convert dirty buffer position to snapshot position in "source"
    val sourceDistance =
      TokenEditDistance.fromBuffer(source, snapshot.text, buffers)
    val snapshotPosition = sourceDistance.toOriginal(
      dirtyPosition.getPosition.getLine,
      dirtyPosition.getPosition.getCharacter
    )

    // Find matching symbol occurrence in SemanticDB snapshot
    val occurrence = for {
      queryPosition <- snapshotPosition.toPosition(dirtyPosition.getPosition)
      occurrence <- snapshot.occurrences
        .find(_.encloses(queryPosition, true))
        // In case of macros we might need to get the postion from the presentation compiler
        .orElse(fromMtags(source, queryPosition))
    } yield occurrence

    ResolvedSymbolOccurrence(sourceDistance, occurrence)
  }

  def definitionFromSnapshot(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams,
      snapshot: TextDocument
  ): DefinitionResult = {
    val ResolvedSymbolOccurrence(sourceDistance, occurrence) =
      positionOccurrence(source, dirtyPosition, snapshot)
    // Find symbol definition location.
    val result: Option[DefinitionResult] = occurrence.flatMap { occ =>
      val isLocal = occ.symbol.isLocal || snapshot.definesSymbol(occ.symbol)
      if (isLocal) {
        // symbol is local so it is defined within the source.
        DefinitionDestination(
          snapshot,
          sourceDistance,
          occ.symbol,
          None,
          dirtyPosition.getTextDocument.getUri
        ).toResult
      } else {
        // symbol is global so it is defined in an external destination buffer.
        DefinitionDestination.fromSymbol(occ.symbol).flatMap(_.toResult)
      }
    }

    result.getOrElse(DefinitionResult.empty(occurrence.fold("")(_.symbol)))
  }

  private def fromMtags(source: AbsolutePath, dirtyPos: Position) = {
    Mtags
      .allToplevels(source.toInput)
      .occurrences
      .find(_.encloses(dirtyPos))
  }

  private case class DefinitionDestination(
      snapshot: TextDocument,
      distance: TokenEditDistance,
      symbol: String,
      path: Option[AbsolutePath],
      uri: String
  ) {

    /** Converts snapshot position to dirty buffer position in the destination file */
    def toResult: Option[DefinitionResult] =
      for {
        location <- snapshot.definition(uri, symbol)
        revisedPosition = distance.toRevised(
          location.getRange.getStart.getLine,
          location.getRange.getStart.getCharacter
        )
        result <- revisedPosition.toLocation(location)
      } yield {
        DefinitionResult(
          Collections.singletonList(result),
          symbol,
          path,
          Some(snapshot)
        )
      }
  }

  private object DefinitionDestination {
    def bestTextDocument(symbolDefinition: SymbolDefinition): TextDocument = {
      val defnRevisedInput = symbolDefinition.path.toInput
      // Read text file from disk instead of editor buffers because the file
      // on disk is more likely to parse.
      val parsed =
        mtags.index(symbolDefinition.path.toLanguage, defnRevisedInput)
      if (parsed.occurrences.isEmpty) {
        // Fall back to SemanticDB on disk, if any
        semanticdbs
          .textDocument(symbolDefinition.path)
          .documentIncludingStale
          .getOrElse(parsed)
      } else {
        parsed
      }
    }

    def fromSymbol(symbol: String): Option[DefinitionDestination] = {
      for {
        symbolDefinition <- index.definition(Symbol(symbol))
        destinationDoc = bestTextDocument(symbolDefinition)
        defnPathInput = symbolDefinition.path.toInputFromBuffers(buffers)
        defnOriginalInput = Input.VirtualFile(
          defnPathInput.path,
          destinationDoc.text
        )
        destinationPath = symbolDefinition.path.toFileOnDisk(workspace)
        destinationDistance = TokenEditDistance(
          defnOriginalInput,
          defnPathInput
        )
      } yield {
        DefinitionDestination(
          destinationDoc,
          destinationDistance,
          symbolDefinition.definitionSymbol.value,
          Some(destinationPath),
          destinationPath.toURI.toString
        )
      }
    }
  }

}
