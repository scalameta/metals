package scala.meta.internal.metals

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
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer
import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.IdTree
import scala.meta.internal.semanticdb.OriginalTree
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
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
    remote: RemoteLanguageServer,
    trees: Trees,
    buildTargets: BuildTargets,
    scalaVersionSelector: ScalaVersionSelector,
    saveDefFileToDisk: Boolean,
    sourceMapper: SourceMapper,
)(implicit ec: ExecutionContext) {

  val destinationProvider = new DestinationProvider(
    index,
    buffers,
    mtags,
    workspace,
    Some(semanticdbs),
    trees,
    buildTargets,
    saveDefFileToDisk,
    sourceMapper,
  )

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
      token: CancelToken,
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
      if (result.isEmpty && path.isScalaFilename) {
        compilers().definition(params, token)
      } else {
        if (fromSemanticdb.isEmpty) {
          warnings.noSemanticdb(path)
        }
        Future.successful(result)
      }
    }
  }

  def fromSymbol(
      sym: String,
      source: Option[AbsolutePath],
  ): ju.List[Location] = {
    destinationProvider.fromSymbol(sym, source) match {
      case None => ju.Collections.emptyList()
      case Some(destination) => destination.locations
    }
  }

  def fromSymbol(
      sym: String,
      targets: List[BuildTargetIdentifier],
  ): ju.List[Location] = {
    destinationProvider
      .fromSymbol(sym, targets.toSet) match {
      case None => ju.Collections.emptyList()
      case Some(r) => r.locations
    }
  }

  /**
   * Returns VirtualFile that contains the definition of
   * the given symbol (of semanticdb).
   */
  def definitionPathInputFromSymbol(
      sym: String,
      source: Option[AbsolutePath],
  ): Option[Input.VirtualFile] =
    destinationProvider
      .definition(sym, source)
      .map(symDef => symDef.path.toInputFromBuffers(buffers))

  def symbolOccurrence(
      source: AbsolutePath,
      dirtyPosition: Position,
  ): Option[(SymbolOccurrence, TextDocument)] = {
    for {
      currentDocument <-
        semanticdbs
          .textDocument(source)
          .documentIncludingStale
      posOcc = positionOccurrence(
        source,
        dirtyPosition,
        currentDocument,
      )
      symbolOccurrence <- {
        def mtagsOccurrence =
          fromMtags(source, dirtyPosition)
        posOcc.occurrence.orElse(mtagsOccurrence)
      }
    } yield (symbolOccurrence, currentDocument)
  }

  /** Convert dirty buffer position to snapshot position in "source" */
  private def positionOccurrenceQuery(
      source: AbsolutePath,
      dirtyPosition: Position,
      snapshot: TextDocument,
  ): (TokenEditDistance, Option[Position]) = {
    val sourceDistance = buffers.tokenEditDistance(source, snapshot.text, trees)
    val snapshotPosition = sourceDistance.toOriginal(
      dirtyPosition.getLine,
      dirtyPosition.getCharacter,
    )
    (sourceDistance, snapshotPosition.toPosition(dirtyPosition))
  }

  private def syntheticApplyOccurrence(
      queryPositionOpt: Option[semanticdb.Range],
      snapshot: TextDocument,
  ) = {
    snapshot.synthetics.collectFirst {
      case Synthetic(
            Some(range),
            SelectTree(_: OriginalTree, Some(IdTree(symbol))),
          )
          if queryPositionOpt
            .exists(queryPos => range == queryPos) =>
        SymbolOccurrence(Some(range), symbol, SymbolOccurrence.Role.REFERENCE)
    }
  }

  def positionOccurrence(
      source: AbsolutePath,
      dirtyPosition: Position,
      snapshot: TextDocument,
  ): ResolvedSymbolOccurrence = {
    val (sourceDistance, queryPositionOpt) =
      positionOccurrenceQuery(source, dirtyPosition, snapshot)

    val occurrence = for {
      queryPosition <- queryPositionOpt
      occurrence <-
        snapshot.occurrences
          .find(_.encloses(queryPosition, true))
          // In case of macros we might need to get the postion from the presentation compiler
          .orElse(fromMtags(source, queryPosition))
    } yield occurrence

    ResolvedSymbolOccurrence(sourceDistance, occurrence)
  }

  /**
   * Find all SymbolOccurrences for the given position.
   * Multiple symbols might be attached, for example
   * extension parameter. see: https://github.com/scalameta/metals/issues/3133
   */
  def positionOccurrences(
      source: AbsolutePath,
      dirtyPosition: Position,
      snapshot: TextDocument,
  ): List[ResolvedSymbolOccurrence] = {
    val (sourceDistance, queryPositionOpt) =
      positionOccurrenceQuery(source, dirtyPosition, snapshot)
    // Find matching symbol occurrences in SemanticDB snapshot
    val occurrence = (for {
      queryPosition <- queryPositionOpt
    } yield {
      val occs = snapshot.occurrences
        .filter(_.encloses(queryPosition, true))
      // In case of macros we might need to get the postion from the presentation compiler
      if (occs.isEmpty)
        fromMtags(source, queryPosition).toList
      else occs
    }).getOrElse(Nil)

    occurrence.map { occ =>
      ResolvedSymbolOccurrence(sourceDistance, Some(occ))
    }.toList
  }

  private def definitionFromSnapshot(
      source: AbsolutePath,
      dirtyPosition: TextDocumentPositionParams,
      snapshot: TextDocument,
  ): DefinitionResult = {
    val ResolvedSymbolOccurrence(sourceDistance, occurrence) =
      positionOccurrence(
        source,
        dirtyPosition.getPosition,
        snapshot,
      )

    // Find symbol definition location.
    def definitionResult(occ: SymbolOccurrence): Option[DefinitionResult] = {
      val isLocal = occ.symbol.isLocal || snapshot.definesSymbol(occ.symbol)
      if (isLocal) {
        // symbol is local so it is defined within the source.
        DefinitionDestination(
          snapshot,
          sourceDistance,
          occ.symbol,
          None,
          dirtyPosition.getTextDocument.getUri,
        ).toResult
      } else {
        // symbol is global so it is defined in an external destination buffer.
        destinationProvider
          .fromSymbol(occ.symbol, Some(source))
      }
    }

    val apply =
      occurrence.flatMap(occ => syntheticApplyOccurrence(occ.range, snapshot))
    val result = occurrence.flatMap(definitionResult)
    val applyResults = apply.flatMap(definitionResult)
    val combined = result ++ applyResults

    if (combined.isEmpty)
      DefinitionResult.empty(occurrence.fold("")(_.symbol))
    else
      combined.reduce(_ ++ _)
  }

  private def fromMtags(source: AbsolutePath, dirtyPos: Position) = {
    Mtags
      .allToplevels(source.toInput, scalaVersionSelector.getDialect(source))
      .occurrences
      .find(_.encloses(dirtyPos))
  }

}

case class DefinitionDestination(
    snapshot: TextDocument,
    distance: TokenEditDistance,
    symbol: String,
    path: Option[AbsolutePath],
    uri: String,
) {

  /**
   * Converts snapshot position to dirty buffer position in the destination file
   */
  def toResult: Option[DefinitionResult] =
    for {
      location <- snapshot.toLocation(uri, symbol)
      revisedPosition = distance.toRevised(
        location.getRange.getStart.getLine,
        location.getRange.getStart.getCharacter,
      )
      result <- revisedPosition.toLocation(location)
    } yield {
      DefinitionResult(
        ju.Collections.singletonList(result),
        symbol,
        path,
        Some(snapshot),
      )
    }

}
class DestinationProvider(
    index: GlobalSymbolIndex,
    buffers: Buffers,
    mtags: Mtags,
    workspace: AbsolutePath,
    semanticdbsFallback: Option[Semanticdbs],
    trees: Trees,
    buildTargets: BuildTargets,
    saveSymbolFileToDisk: Boolean,
    sourceMapper: SourceMapper,
) {

  private def bestTextDocument(
      symbolDefinition: SymbolDefinition
  ): TextDocument = {
    val defnRevisedInput = symbolDefinition.path.toInput
    // Read text file from disk instead of editor buffers because the file
    // on disk is more likely to parse.
    lazy val parsed = {
      mtags.index(
        symbolDefinition.path.toLanguage,
        defnRevisedInput,
        symbolDefinition.dialect,
      )
    }

    val path = symbolDefinition.path
    if (path.isAmmoniteScript || parsed.occurrences.isEmpty) {
      // Fall back to SemanticDB on disk, if any

      def fromSemanticdbs(p: AbsolutePath): Option[TextDocument] =
        semanticdbsFallback.flatMap(_.textDocument(p).documentIncludingStale)

      fromSemanticdbs(path)
        .orElse(
          sourceMapper.mappedTo(path).flatMap(fromSemanticdbs)
        )
        .getOrElse(parsed)

    } else {
      parsed
    }
  }

  def definition(
      symbol: String,
      source: Option[AbsolutePath],
  ): Option[SymbolDefinition] = {
    val targets = source.map(sourceToAllowedBuildTargets).getOrElse(Set.empty)
    definition(symbol, targets)
  }

  def definition(
      symbol: String,
      allowedBuildTargets: Set[BuildTargetIdentifier],
  ): Option[SymbolDefinition] = {
    val definitions = index.definitions(Symbol(symbol)).filter(_.path.exists)
    if (allowedBuildTargets.isEmpty)
      definitions.headOption
    else {
      val matched = definitions.find { defn =>
        sourceBuildTargets(defn.path).exists(id =>
          allowedBuildTargets.contains(id)
        )
      }
      // Fallback to any definition - it's needed for worksheets
      // They might have dynamic `import $dep` and these sources jars
      // aren't registered in buildTargets
      matched.orElse(definitions.headOption)
    }
  }

  private def sourceBuildTargets(
      source: AbsolutePath
  ): List[BuildTargetIdentifier] = {
    def trivialSource: Option[List[BuildTargetIdentifier]] =
      buildTargets.sourceBuildTargets(source).map(_.toList).filter(_.nonEmpty)

    def dependencySource: Option[List[BuildTargetIdentifier]] = {
      source.jarPath
        .map(path => buildTargets.inverseDependencySource(path).toList)
        .filter(_.nonEmpty)
    }

    trivialSource
      .orElse(dependencySource)
      .orElse(buildTargets.sbtBuildScalaTarget(source).map(List(_)))
      .getOrElse(List.empty)
  }

  def fromSymbol(
      symbol: String,
      allowedBuildTargets: Set[BuildTargetIdentifier],
  ): Option[DefinitionResult] = {
    definition(symbol, allowedBuildTargets).flatMap { defn =>
      val destinationPath =
        if (saveSymbolFileToDisk) defn.path.toFileOnDisk(workspace)
        else defn.path
      val uri = destinationPath.toURI.toString

      defn.range match {
        // read only source - no need to adjust positions
        case Some(range) if defn.path.isJarFileSystem =>
          Some(
            DefinitionResult(
              ju.Collections.singletonList(range.toLocation(uri)),
              symbol,
              Some(defn.path),
              None,
            )
          )
        case _ =>
          val destinationDoc = bestTextDocument(defn)
          val destinationDistance =
            buffers.tokenEditDistance(
              destinationPath,
              destinationDoc.text,
              trees,
            )
          DefinitionDestination(
            destinationDoc,
            destinationDistance,
            defn.definitionSymbol.value,
            Some(destinationPath),
            uri,
          ).toResult
      }
    }
  }

  def fromSymbol(
      symbol: String,
      source: Option[AbsolutePath],
  ): Option[DefinitionResult] = {
    val targets = source.map(sourceToAllowedBuildTargets).getOrElse(Set.empty)
    fromSymbol(symbol, targets)
  }

  private def sourceToAllowedBuildTargets(
      source: AbsolutePath
  ): Set[BuildTargetIdentifier] = {
    buildTargets.inverseSources(source) match {
      case None => Set.empty
      case Some(id) => buildTargets.buildTargetTransitiveDependencies(id).toSet
    }
  }
}
