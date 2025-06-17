package scala.meta.internal.metals

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.inputs.Input
import scala.meta.inputs.Position.Range
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.KeywordWrapper.Scala3SoftKeywords
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb
import scala.meta.internal.semanticdb.IdTree
import scala.meta.internal.semanticdb.OriginalTree
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
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
    semanticdbs: () => Semanticdbs,
    compilers: () => Compilers,
    trees: Trees,
    buildTargets: BuildTargets,
    scalaVersionSelector: ScalaVersionSelector,
    saveDefFileToDisk: Boolean,
    sourceMapper: SourceMapper,
)(implicit ec: ExecutionContext, rc: ReportContext) {

  private val fallback = new FallbackDefinitionProvider(trees, index)
  val destinationProvider = new DestinationProvider(
    index,
    buffers,
    mtags,
    workspace,
    () => Some(semanticdbs()),
    buildTargets,
    saveDefFileToDisk,
    sourceMapper,
    scalaVersionSelector,
  )

  val scaladocDefinitionProvider =
    new ScaladocDefinitionProvider(buffers, trees, destinationProvider)

  def definition(
      path: AbsolutePath,
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[DefinitionResult] = {
    val reportBuilder =
      new DefinitionProviderReportBuilder(path, params, buffers)
    val scalaVersion = scalaVersionSelector.scalaVersionForPath(path)
    val isScala3 = ScalaVersions.isScala3Version(scalaVersion)

    def fromCompiler() =
      if (path.isScalaFilename) {
        compilers()
          .definition(params, token)
          .map {
            case res if res.isEmpty =>
              reportBuilder.setCompilerResult(res)
              Some(res)
            case res =>
              val pathToDef = res.locations.asScala.head.getUri.toAbsolutePath
              Some(
                res.copy(semanticdb =
                  semanticdbs().textDocument(pathToDef).documentIncludingStale
                )
              )
          }
      } else Future.successful(None)

    def fromSemanticDb() = Future.successful {
      semanticdbs()
        .textDocument(path)
        .documentIncludingStale
        .map(definitionFromSnapshot(path, params, _))
        .map(reportBuilder.setSemanticDBResult)
    }

    def fromScalaDoc() = Future.successful {
      scaladocDefinitionProvider
        .definition(path, params, isScala3)
        .map(reportBuilder.setFoundScaladocDef)
    }

    def fromFallback() =
      Future.successful(
        fallback
          .search(path, params.getPosition(), isScala3, reportBuilder)
          .map(reportBuilder.setFallbackResult)
      )

    // Scala 3 prior to 3.7.0 has a bug where the definition is much slower
    def scala3DefinitionBugFixed: Boolean =
      SemVer.isCompatibleVersion("3.7.0", scalaVersion) ||
        scalaVersion.startsWith(
          "3.3."
        ) && // LTS 3.3.7 will include fixes from 3.7.x
        SemVer.isCompatibleVersion("3.3.7", scalaVersion)

    val shouldUseOldOrder = isScala3 && !scala3DefinitionBugFixed

    val strategies: List[() => Future[Option[DefinitionResult]]] =
      if (shouldUseOldOrder)
        List(fromSemanticDb, fromCompiler, fromScalaDoc, fromFallback)
      else List(fromCompiler, fromSemanticDb, fromScalaDoc, fromFallback)

    for {
      result <- strategies.foldLeft(Future.successful(DefinitionResult.empty)) {
        case (acc, next) =>
          acc.flatMap {
            case res if res.isEmpty && !res.symbol.endsWith("/") =>
              next().map(_.getOrElse(res))
            case res => Future.successful(res)
          }
      }
    } yield {
      reportBuilder
        .build(scalaVersionSelector)
        .foreach(r => rc.unsanitized().create(() => r))
      result
    }
  }

  def definition(
      path: AbsolutePath,
      pos: Int,
  ): Future[DefinitionResult] = {
    val text = path.readText
    val input = new Input.VirtualFile(path.toURI.toString(), text)
    val range = Range(input, pos, pos)
    definition(
      path,
      new TextDocumentPositionParams(
        new TextDocumentIdentifier(path.toURI.toString()),
        range.toLsp.getStart(),
      ),
      EmptyCancelToken,
    )
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
        semanticdbs()
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
    val sourceDistance =
      buffers.tokenEditDistance(source, snapshot.text, scalaVersionSelector)
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
          .find(occ =>
            // empty range is set for anon classes definition
            occ.range.exists(!_.isPoint) && occ.encloses(queryPosition, true)
          )
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
          occ.symbol,
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
    querySymbol: String,
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
        querySymbol,
      )
    }

}
class DestinationProvider(
    index: GlobalSymbolIndex,
    buffers: Buffers,
    mtags: Mtags,
    workspace: AbsolutePath,
    semanticdbsFallback: () => Option[Semanticdbs],
    buildTargets: BuildTargets,
    saveSymbolFileToDisk: Boolean,
    sourceMapper: SourceMapper,
    scalaVersionSelector: ScalaVersionSelector,
) {

  def findDefinitionFile(symbol: String): List[AbsolutePath] = {
    index.findFileForToplevel(Symbol(symbol).toplevel).map(_._1)
  }

  private def bestTextDocument(
      symbolDefinition: SymbolDefinition
  ): TextDocument = {
    // Read text file from disk instead of editor buffers because the file
    // on disk is more likely to parse.
    lazy val parsed = {
      mtags.index(
        symbolDefinition.path.toLanguage,
        symbolDefinition.path,
        symbolDefinition.dialect,
      )
    }

    val path = symbolDefinition.path
    if (path.isScalaScript || parsed.occurrences.isEmpty) {
      // Fall back to SemanticDB on disk, if any

      def fromSemanticdbs(p: AbsolutePath): Option[TextDocument] =
        semanticdbsFallback().flatMap(_.textDocument(p).documentIncludingStale)

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
              defn.definitionSymbol.value,
              Some(defn.path),
              None,
              defn.querySymbol.value,
            )
          )
        case _ =>
          val destinationDoc = bestTextDocument(defn)
          val destinationDistance =
            buffers.tokenEditDistance(
              destinationPath,
              destinationDoc.text,
              scalaVersionSelector,
            )
          DefinitionDestination(
            destinationDoc,
            destinationDistance,
            defn.definitionSymbol.value,
            Some(destinationPath),
            uri,
            defn.querySymbol.value,
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

class DefinitionProviderReportBuilder(
    path: AbsolutePath,
    params: TextDocumentPositionParams,
    buffers: Buffers,
) {
  private var compilerDefn: Option[DefinitionResult] = None
  private var semanticDBDefn: Option[DefinitionResult] = None

  private var fallbackDefn: Option[DefinitionResult] = None
  private var nonLocalGuesses: List[String] = List.empty

  private var foundScalaDocDef = false

  private var error: Option[Throwable] = None

  def setCompilerResult(result: DefinitionResult): DefinitionResult = {
    compilerDefn = Some(result)
    result
  }

  def setSemanticDBResult(result: DefinitionResult): DefinitionResult = {
    semanticDBDefn = Some(result)
    result
  }

  def setFallbackResult(result: DefinitionResult): DefinitionResult = {
    fallbackDefn = Some(result)
    result
  }

  def setError(e: Throwable): Unit = {
    error = Some(e)
  }

  def setNonLocalGuesses(guesses: List[String]): Unit = {
    nonLocalGuesses = guesses
  }

  def setFoundScaladocDef(result: DefinitionResult): DefinitionResult = {
    foundScalaDocDef = true
    result
  }

  def build(scalaVersionSelector: ScalaVersionSelector): Option[Report] = {

    val text = buffers.get(path).getOrElse("")
    val dialect = scalaVersionSelector.getDialect(path)
    val tokens = text.safeTokenize(dialect)
    def shouldOfferDefinition(token: Token) = token match {
      case token: Token.Ident if !Scala3SoftKeywords.contains(token.text) =>
        true
      case _: Token.Interpolation.Id => true
      case _ => false
    }
    val shouldProduceReport =
      text.nonEmpty && tokens.toOption.toList.flatMap(_.tokens).exists {
        case token
            if token.pos.contains(
              params.getPosition()
            ) && shouldOfferDefinition(token) =>
          true
        case _ => false
      }

    compilerDefn match {
      case Some(compilerDefn)
          if !foundScalaDocDef && compilerDefn.isEmpty &&
            !compilerDefn.querySymbol.endsWith("/") && shouldProduceReport =>

        Some(
          Report(
            "empty-definition",
            s"""|empty definition using pc, found symbol in pc: ${compilerDefn.querySymbol}
                |${semanticDBDefn match {
                 case None =>
                   s"semanticdb not found"
                 case Some(defn) if defn.isEmpty =>
                   s"empty definition using semanticdb"
                 case Some(defn) =>
                   s"found definition using semanticdb; symbol ${defn.symbol}"
               }}
                |${fallbackDefn.filterNot(_.isEmpty) match {
                 case None =>
                   s"""empty definition using fallback
                |non-local guesses:
                |${if (nonLocalGuesses.isEmpty) "" else nonLocalGuesses.mkString("\t -", "\n\t -", "")}"""
                 case Some(defn) =>
                   s"\nfound definition using fallback; symbol ${defn.symbol}"
               }}
                |${params.printed(buffers)}
                |""".stripMargin,
            s"empty definition using pc, found symbol in pc: ${compilerDefn.querySymbol}",
            path = ju.Optional.of(path.toURI),
            id = querySymbol.map(s => s"${path.toURI}:$s").asJava,
            error = error,
          )
        )
      case _ => None
    }
  }
  private def querySymbol: Option[String] =
    compilerDefn.map(_.querySymbol) match {
      case Some("") | None =>
        semanticDBDefn
          .map(_.querySymbol)
          .orElse(fallbackDefn.map(_.querySymbol))
      case res => res
    }
}
