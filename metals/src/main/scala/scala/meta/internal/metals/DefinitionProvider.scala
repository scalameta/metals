package scala.meta.internal.metals

import java.util.Collections
import org.eclipse.lsp4j.TextDocumentPositionParams
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

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
    icons: Icons
)(implicit statusBar: StatusBar) {

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams
  ): DefinitionResult = {
    val result = semanticdbs.textDocument(path)
    result.documentIncludingStale match {
      case Some(doc) =>
        definitionFromSnapshot(path, params, doc)
      case _ =>
        statusBar.addMessage(s"${icons.alert} No SemanticDB")
        DefinitionResult.empty
    }
  }

  private def definitionFromSnapshot(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams,
      snapshot: TextDocument
  ): DefinitionResult = {
    // Step 1: convert dirty buffer position to snapshot position in "source"
    val bufferInput = source.toInputFromBuffers(buffers)
    val snapshotInput = Input.VirtualFile(bufferInput.path, snapshot.text)
    val sourceDistance = TokenEditDistance(snapshotInput, bufferInput)
    val snapshotPosition = sourceDistance.toOriginal(
      dirtyPosition.getPosition.getLine,
      dirtyPosition.getPosition.getCharacter
    )

    // Step 2: find matching symbol occurrence in SemanticDB snapshot
    val occurrence = for {
      queryPosition <- snapshotPosition.foldResult(
        onPosition = pos => {
          dirtyPosition.getPosition.setLine(pos.startLine)
          dirtyPosition.getPosition.setCharacter(pos.startColumn)
          Some(dirtyPosition.getPosition)
        },
        onUnchanged = () => Some(dirtyPosition.getPosition),
        onNoMatch = () => None
      )
      occurrence <- snapshot.occurrences.find(_.encloses(queryPosition))
    } yield occurrence

    // Step 3: find symbol definition
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
        result <- revisedPosition.foldResult(
          pos => {
            val start = location.getRange.getStart
            start.setLine(pos.startLine)
            start.setCharacter(pos.startColumn)
            val end = location.getRange.getEnd
            end.setLine(pos.endLine)
            end.setCharacter(pos.endColumn)
            Some(location)
          },
          () => Some(location),
          () => None
        )
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
      // Read text file from disk instead of editor buffers.
      val parsed =
        mtags.index(symbolDefinition.path.toLanguage, defnRevisedInput)
      if (parsed.occurrences.isEmpty) {
        // Fall back to SemanticDB on disk, if any
        semanticdbs
          .textDocument(symbolDefinition.path)
          .documentIncludingStale match {
          case Some(d) => d
          case _ => parsed
        }
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
