package scala.meta.internal.metals

import java.util.Collections
import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.remotels.RemoteLanguageServer
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentPositionParams

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
    warnings: Warnings,
    compilers: () => Compilers,
    remote: RemoteLanguageServer
)(implicit ec: ExecutionContext) {

  val destinationProvider = new DestinationProvider(
    index,
    buffers,
    mtags,
    workspace,
    Some(semanticdbs)
  )

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[DefinitionResult] = {
    val fromSemanticdb =
      semanticdbs.textDocument(path).documentIncludingStale
    val fromSnapshot = fromSemanticdb match {
      case Some(doc) =>
        definitionFromSnapshot(path, params, doc)
      case _ =>
        DefinitionResult.empty
    }
    val fromIndex =
      if (fromSnapshot.isEmpty && remote.isEnabledForPath(path)) {
        remote.definition(params).map(_.getOrElse(fromSnapshot))
      } else {
        Future.successful(fromSnapshot)
      }
    fromIndex.flatMap { result =>
      if (result.isEmpty) {
        compilers().definition(params, token)
      } else {
        if (fromSemanticdb.isEmpty) {
          warnings.noSemanticdb(path)
        }
        Future.successful(result)
      }
    }
  }

  def fromSymbol(sym: String): ju.List[Location] =
    destinationProvider.fromSymbol(sym).flatMap(_.toResult) match {
      case None => ju.Collections.emptyList()
      case Some(destination) => destination.locations
    }

  /**
   * Returns VirtualFile that contains the definition of
   * the given symbol (of semanticdb).
   */
  def definitionPathInputFromSymbol(
      sym: String
  ): Option[Input.VirtualFile] =
    index
      .definition(Symbol(sym))
      .map(symDef => symDef.path.toInputFromBuffers(buffers))

  def symbolOccurrence(
      source: AbsolutePath,
      dirtyPosition: Position
  ): Option[(SymbolOccurrence, TextDocument)] = {
    for {
      currentDocument <-
        semanticdbs
          .textDocument(source)
          .documentIncludingStale
      posOcc = positionOccurrence(
        source,
        dirtyPosition,
        currentDocument
      )
      symbolOccurrence <- {
        def mtagsOccurrence =
          fromMtags(source, dirtyPosition)
        posOcc.occurrence.orElse(mtagsOccurrence)
      }
    } yield (symbolOccurrence, currentDocument)
  }

  def positionOccurrence(
      source: AbsolutePath,
      dirtyPosition: Position,
      snapshot: TextDocument
  ): ResolvedSymbolOccurrence = {
    // Convert dirty buffer position to snapshot position in "source"
    val sourceDistance = buffers.tokenEditDistance(source, snapshot.text)
    val snapshotPosition = sourceDistance.toOriginal(
      dirtyPosition.getLine,
      dirtyPosition.getCharacter
    )

    // Find matching symbol occurrence in SemanticDB snapshot
    val occurrence = for {
      queryPosition <- snapshotPosition.toPosition(dirtyPosition)
      occurrence <-
        snapshot.occurrences
          .find(_.encloses(queryPosition, true))
          // In case of macros we might need to get the postion from the presentation compiler
          .orElse(fromMtags(source, queryPosition))
    } yield occurrence

    ResolvedSymbolOccurrence(sourceDistance, occurrence)
  }

  private def definitionFromSnapshot(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams,
      snapshot: TextDocument
  ): DefinitionResult = {
    val ResolvedSymbolOccurrence(sourceDistance, occurrence) =
      positionOccurrence(source, dirtyPosition.getPosition, snapshot)
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
        destinationProvider.fromSymbol(occ.symbol).flatMap(_.toResult)
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

}

case class DefinitionDestination(
    snapshot: TextDocument,
    distance: TokenEditDistance,
    symbol: String,
    path: Option[AbsolutePath],
    uri: String
) {

  /**
   * Converts snapshot position to dirty buffer position in the destination file */
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

class DestinationProvider(
    index: GlobalSymbolIndex,
    buffers: Buffers,
    mtags: Mtags,
    workspace: AbsolutePath,
    semanticdbsFallback: Option[Semanticdbs]
) {

  private def bestTextDocument(
      symbolDefinition: SymbolDefinition
  ): TextDocument = {
    val defnRevisedInput = symbolDefinition.path.toInput
    // Read text file from disk instead of editor buffers because the file
    // on disk is more likely to parse.
    lazy val parsed =
      mtags.index(symbolDefinition.path.toLanguage, defnRevisedInput)

    if (symbolDefinition.path.isAmmoniteScript || parsed.occurrences.isEmpty) {
      // Fall back to SemanticDB on disk, if any
      semanticdbsFallback
        .flatMap {
          _.textDocument(symbolDefinition.path).documentIncludingStale
        }
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
