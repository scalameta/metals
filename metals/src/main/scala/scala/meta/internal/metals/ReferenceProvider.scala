package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal
import scala.util.matching.Regex

import scala.meta.Importee
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ResolvedSymbolOccurrence
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.CompletionItemPriority

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams

final class ReferenceProvider(
    workspace: AbsolutePath,
    semanticdbs: () => Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    trees: Trees,
    buildTargets: BuildTargets,
    compilers: Compilers,
    scalaVersionSelector: ScalaVersionSelector,
)(implicit ec: ExecutionContext)
    extends SemanticdbFeatureProvider
    with CompletionItemPriority {
  val index: TrieMap[Path, IdentifierIndex.MaybeStaleIndexEntry] =
    TrieMap.empty
  val identifierIndex: IdentifierIndex = new IdentifierIndex
  val pcReferencesLock = new AtomicReference(Lock.completed)

  def addIdentifiers(file: AbsolutePath, set: Iterable[String]): Unit =
    buildTargets
      .inverseSources(file)
      .map(id => identifierIndex.addIdentifiers(file, id, set))

  def didChange(
      path: AbsolutePath,
      text: String,
  ): Future[Unit] = Future {
    buildTargets.inverseSources(path).map { id =>
      val dialect = scalaVersionSelector.getDialect(path)
      val set = identifierIndex.collectIdentifiers(text, dialect)
      identifierIndex.addIdentifiers(path, id, set)
      index.updateWith(path.toNIO) {
        case Some(entry) if !entry.isStale => Some(entry.asStale)
        case optEntry => optEntry
      }
    }
  }

  type SymbolName = String
  val workspaceMemberPriority: TrieMap[SymbolName, Int] = TrieMap.empty

  override def reset(): Unit = {
    index.clear()
    workspaceMemberPriority.clear()
  }

  private def updateWorkspaceMemberPriority(
      symbol: String,
      change: Int,
  ): Unit = {
    if (change != 0)
      workspaceMemberPriority.updateWith(symbol) { priority =>
        Some(priority.getOrElse(0) + change)
      }
  }

  override def onDelete(file: AbsolutePath): Unit = {
    index.get(file.toNIO).foreach {
      case IdentifierIndex.MaybeStaleIndexEntry(_, bloom, _) =>
        workspaceMemberPriority.foreach { case (symbol, _) =>
          if (bloom.mightContain(symbol))
            updateWorkspaceMemberPriority(symbol, -1)
        }
    }
    index.remove(file.toNIO)
  }

  override def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    buildTargets.inverseSources(file).map { id =>
      val count = docs.documents.foldLeft(0)(_ + _.occurrences.length)
      val syntheticsCount = docs.documents.foldLeft(0)(_ + _.synthetics.length)
      val bloom = BloomFilter.create(
        Funnels.stringFunnel(StandardCharsets.UTF_8),
        Integer.valueOf((count + syntheticsCount) * 2),
        0.01,
      )

      val oldEntry = index.get(file.toNIO)

      val entry =
        IdentifierIndex.MaybeStaleIndexEntry(id, bloom, isStale = false)
      index(file.toNIO) = entry
      docs.documents.foreach { d =>
        d.occurrences.foreach { o =>
          bloom.put(o.symbol)
        }
        d.synthetics.foreach { synthetic =>
          Synthetics.foreachSymbol(synthetic) { sym =>
            bloom.put(sym)
            Synthetics.Continue
          }
        }
      }
      workspaceMemberPriority.foreach { case (symbol, _) =>
        val shift = oldEntry
          .exists(_.bloom.mightContain(symbol))
          .compare(
            bloom.mightContain(symbol)
          )
        if (shift != 0)
          updateWorkspaceMemberPriority(
            symbol,
            -shift,
          )
      }
    }
  }

  def referencesForWildcardImport(
      owner: String,
      source: AbsolutePath,
      directlyImportedSymbols: Set[String],
  ): List[String] = {

    semanticdbs().textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        doc.occurrences
          .map(_.symbol)
          .distinct
          .filter { s =>
            s.startsWith(owner) && s.isType && !directlyImportedSymbols
              .contains(s)
          }
          .toList
      case None => List()
    }
  }

  /**
   * Find references for the given params.
   *
   * @return - All found list of references, it is a list of result because
   *           in some cases, multiple symbols are attached to the given position.
   *           (e.g. extension parameter). See: https://github.com/scalameta/scalameta/issues/2443
   */
  def references(
      params: ReferenceParams,
      findRealRange: AdjustRange = noAdjustRange,
      includeSynthetics: Synthetic => Boolean = _ => true,
  )(implicit report: ReportContext): Future[List[ReferencesResult]] = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    val textDoc = semanticdbs().textDocument(source)
    val supportsPcRefs =
      buildTargets.inverseSources(source).exists(buildTargets.supportsPcRefs(_))
    val textDocOpt =
      if (supportsPcRefs) textDoc.toOption else textDoc.documentIncludingStale
    textDocOpt match {
      case Some(doc) =>
        val results: List[ResolvedSymbolOccurrence] = {
          val posOccurrences =
            definition.positionOccurrences(source, params.getPosition, doc)
          if (posOccurrences.isEmpty)
            // handling case `import a.{A as @@B}`
            occurrencesForRenamedImport(source, params, doc)
          else posOccurrences
        }
        if (results.isEmpty) {
          scribe.debug(
            s"No symbol found at ${params.getPosition()} for $source"
          )
        }
        val semanticdbResult = Future.sequence {
          results.map { result =>
            val occurrence = result.occurrence.get
            val distance = result.distance
            val alternatives =
              referenceAlternatives(occurrence.symbol, source, doc)
            references(
              source,
              params,
              doc,
              distance,
              occurrence,
              alternatives,
              params.getContext.isIncludeDeclaration,
              findRealRange,
              includeSynthetics,
              supportsPcRefs,
            ).map { locations =>
              // It's possible to return nothing is we exclude declaration
              if (
                locations.isEmpty && params.getContext().isIncludeDeclaration()
              ) {
                val fileInIndex =
                  if (index.contains(source.toNIO))
                    s"Current file ${source} is present"
                  else s"Missing current file ${source}"
                scribe.debug(
                  s"No references found, index size ${index.size}\n" + fileInIndex
                )
                report
                  .unsanitized()
                  .create(() =>
                    Report(
                      "empty-references",
                      index
                        .map { case (path, entry) =>
                          s"$path -> ${entry.bloom.approximateElementCount()}"
                        }
                        .mkString("\n"),
                      s"Could not find any locations for ${result.occurrence}, printing index state",
                      path = Optional.of(source.toURI),
                      id = Optional.of(
                        source.toString() + ":" + result.occurrence
                          .getOrElse("")
                      ),
                    )
                  )
              }
              ReferencesResult(occurrence.symbol, locations)
            }
          }
        }
        val pcResult =
          pcReferences(
            source,
            results.flatMap(_.occurrence).map(_.symbol),
            params.getContext().isIncludeDeclaration(),
            findRealRange,
          )

        Future
          .sequence(List(semanticdbResult, pcResult))
          .map(
            _.flatten
              .groupBy(_.symbol)
              .collect { case (symbol, refs) =>
                ReferencesResult(symbol, refs.flatMap(_.locations))
              }
              .toList
          )
      case None =>
        if (textDoc.documentIncludingStale.isEmpty)
          scribe.debug(s"No semanticDB for $source")
        else scribe.debug(s"Stale semanticDB for $source")
        val includeDeclaration = params.getContext().isIncludeDeclaration()
        for {
          foundRefs <- compilers.references(
            params,
            EmptyCancelToken,
            findRealRange,
          )
          symbols = foundRefs.map(_.symbol).filterNot(_.isLocal)
          fromPc <-
            if (symbols.isEmpty) Future.successful(Nil)
            else {
              pcReferences(
                source,
                symbols,
                includeDeclaration,
                findRealRange,
              )
            }
        } yield {
          if (symbols.isEmpty) foundRefs
          else {
            val fromWorkspace = workspaceReferences(
              source,
              symbols.toSet,
              includeDeclaration,
              findRealRange,
              includeSynthetics,
            )
            val results = ReferencesResult(
              symbols.head,
              fromWorkspace,
            ) :: (fromPc ++ foundRefs)

            results
              .groupBy(_.symbol)
              .collect { case (symbol, refs) =>
                ReferencesResult(symbol, refs.flatMap(_.locations))
              }
              .toList
          }
        }
    }
  }

  // for `import package.{AA as B@@B}` we look for occurrences at `import package.{@@AA as BB}`,
  // since rename is not a position occurrence in semanticDB
  private def occurrencesForRenamedImport(
      source: AbsolutePath,
      params: ReferenceParams,
      document: TextDocument,
  ): List[ResolvedSymbolOccurrence] =
    (for {
      rename <- trees.findLastEnclosingAt[Importee.Rename](
        source,
        params.getPosition(),
      )
    } yield definition.positionOccurrences(
      source,
      rename.name.pos.toLsp.getStart(),
      document,
    )).getOrElse(List())

  // Returns alternatives symbols for which "goto definition" resolves to the occurrence symbol.
  private def referenceAlternatives(
      symbol: String,
      fromSource: AbsolutePath,
      referenceDoc: TextDocument,
  ): Set[String] = {
    val definitionDoc = if (referenceDoc.symbols.exists(_.symbol == symbol)) {
      Some((fromSource, referenceDoc))
    } else {
      for {
        location <- definition
          .fromSymbol(symbol, Some(fromSource))
          .asScala
          .headOption
        source = location.getUri().toAbsolutePath
        definitionDoc <- semanticdbs()
          .textDocument(source)
          .documentIncludingStale
      } yield (source, definitionDoc)
    }

    definitionDoc match {
      case Some((defPath, definitionDoc)) =>
        val name = symbol.desc.name.value
        val alternatives = new SymbolAlternatives(symbol, name)

        def candidates(check: SymbolInformation => Boolean) = for {
          info <- definitionDoc.symbols
          if check(info)
        } yield info.symbol

        val isCandidate =
          if (defPath.isJava)
            candidates { info =>
              alternatives.isJavaConstructor(info)
            }.toSet
          else
            candidates { info =>
              alternatives.isVarSetter(info) ||
              alternatives.isCompanionObject(info) ||
              alternatives.isCompanionClass(info) ||
              alternatives.isCopyOrApplyParam(info) ||
              alternatives.isContructorParam(info)
            }.toSet

        val nonSyntheticSymbols = for {
          occ <- definitionDoc.occurrences
          if isCandidate(occ.symbol) || occ.symbol == symbol
          if occ.role.isDefinition
        } yield occ.symbol

        def isSyntheticSymbol = !nonSyntheticSymbols.contains(symbol)

        def additionalAlternativesForSynthetic = for {
          info <- definitionDoc.symbols
          if info.symbol != name
          if {
            alternatives.isCompanionClass(info) ||
            alternatives.isFieldParam(info)
          }
        } yield info.symbol

        if (defPath.isJava)
          isCandidate
        else if (isSyntheticSymbol)
          isCandidate ++ additionalAlternativesForSynthetic
        else
          isCandidate
      case None => Set.empty
    }
  }

  private def pcReferences(
      path: AbsolutePath,
      symbols: List[String],
      includeDeclaration: Boolean,
      adjustLocation: AdjustRange,
  ): Future[List[ReferencesResult]] = {
    val visited = mutable.Set[AbsolutePath]()
    val names = symbols.map(nameFromSymbol(_)).toSet
    val pathsWithId =
      for {
        buildTarget <- buildTargets.inverseSources(path).toList
        name <- names
        pathsMap = pathsForName(buildTarget, name)
        id <- pathsMap.keySet
      } yield {
        val searchFiles = pathsMap(id).filter { searchFile =>
          val indexedFile = index.get(searchFile.toNIO)
          (indexedFile.isEmpty || indexedFile.get.shouldUsePc(
            buildTargets
          )) && !visited(
            searchFile
          )
        }.distinct
        visited ++= searchFiles
        (id -> searchFiles)
      }

    val lazyResults = pathsWithId
      .groupMap(_._1)(_._2)
      .map { case (id, searchFiles) =>
        (isCancelled: IsCancelled) =>
          compilers.references(
            id,
            searchFiles.flatten,
            includeDeclaration,
            symbols,
            adjustLocation,
            isCancelled,
          )
      }
      .toList
    val lock = new Lock
    val result =
      pcReferencesLock.getAndSet(lock).cancelAndWaitUntilCompleted().flatMap {
        _ =>
          val maxPcsNumber = Runtime.getRuntime().availableProcessors() / 2
          executeBatched(lazyResults, maxPcsNumber, () => lock.isCancelled)
            .map(_.flatten)
      }
    result.onComplete(_ => lock.complete())
    result
  }

  private def nameFromSymbol(
      semanticDBSymbol: String
  ): String = {
    val desc = semanticDBSymbol.desc
    val actualSym =
      if (
        desc.isMethod && (desc.name.value == "apply" || desc.name.value == "unapply")
      ) {
        val owner = semanticDBSymbol.owner
        if (owner != s.Scala.Symbols.None) owner.desc
        else desc
      } else desc
    actualSym.name.value
  }

  private def pathsForName(
      buildTarget: BuildTargetIdentifier,
      name: String,
  ): Map[BuildTargetIdentifier, List[AbsolutePath]] = {
    val allowedBuildTargets = buildTargets.allInverseDependencies(buildTarget)
    val visited = scala.collection.mutable.Set.empty[AbsolutePath]
    val foundPaths = for {
      (path, entry) <- identifierIndex.index.iterator
      if allowedBuildTargets.contains(entry.id) &&
        entry.bloom.mightContain(name)
      sourcePath = AbsolutePath(path)
      if !visited(sourcePath)
      _ = visited.add(sourcePath)
      if sourcePath.exists
    } yield (entry.id, sourcePath)
    foundPaths.toList.groupMap(_._1)(_._2)
  }

  /**
   * Return all paths to files which contain at least one symbol from isSymbol set.
   */
  private def pathsFor(
      buildTargetSet: Set[BuildTargetIdentifier],
      isSymbol: Set[String],
      includeStale: Boolean,
  ): Iterator[AbsolutePath] = {
    if (buildTargetSet.isEmpty) Iterator.empty
    else {
      val allowedBuildTargets =
        buildTargetSet.flatMap(buildTargets.allInverseDependencies)
      val visited = scala.collection.mutable.Set.empty[AbsolutePath]
      val result = for {
        (path, entry) <- index.iterator
        if includeStale || !entry.shouldUsePc(
          buildTargets
        ) || path.filename.isJavaFilename
        if allowedBuildTargets(entry.id) &&
          isSymbol.exists(entry.bloom.mightContain)
        sourcePath = AbsolutePath(path)
        if !visited(sourcePath)
        _ = visited.add(sourcePath)
        if sourcePath.exists
      } yield sourcePath

      result
    }
  }

  override def workspaceMemberPriority(
      symbol: String
  ): Integer =
    workspaceMemberPriority.getOrElseUpdate(
      symbol, {
        val visited = scala.collection.mutable.Set.empty[AbsolutePath]
        index.iterator.count { case (path, entry) =>
          if (entry.bloom.mightContain(symbol)) {
            val sourcePath = AbsolutePath(path)
            visited.add(sourcePath) && sourcePath.exists
          } else false
        }
      },
    ) * -1

  def workspaceReferences(
      source: AbsolutePath,
      isSymbol: Set[String],
      isIncludeDeclaration: Boolean,
      findRealRange: AdjustRange = noAdjustRange,
      includeSynthetics: Synthetic => Boolean = _ => true,
      sourceContainsDefinition: Boolean = false,
  ): Seq[Location] = {
    val definitionPaths =
      if (sourceContainsDefinition) Set(source)
      else {
        val foundDefinitionLocations =
          isSymbol.flatMap(definition.destinationProvider.findDefinitionFile)

        if (foundDefinitionLocations.isEmpty) Set(source)
        else foundDefinitionLocations
      }

    val definitionBuildTargets =
      definitionPaths.flatMap { path =>
        buildTargets.inverseSourcesAll(path).toSet
      }

    val result = for {
      sourcePath <- pathsFor(
        definitionBuildTargets,
        isSymbol,
        includeStale = false,
      )
      semanticdb <-
        semanticdbs()
          .textDocument(sourcePath)
          .documentIncludingStale
          .iterator
      semanticdbDistance = buffers.tokenEditDistance(
        sourcePath,
        semanticdb.text,
        scalaVersionSelector,
      )
      uri = sourcePath.toURI.toString
      reference <-
        try {
          referenceLocations(
            semanticdb,
            isSymbol,
            semanticdbDistance,
            uri,
            isIncludeDeclaration,
            findRealRange,
            includeSynthetics,
            sourcePath.isJava,
          )
        } catch {
          case NonFatal(e) =>
            // Can happen for example if the SemanticDB text is empty for some reason.
            scribe.error(s"reference: $sourcePath", e)
            Nil
        }
    } yield reference
    result.toSeq
  }

  /**
   * Return all paths to files which contain at least one symbol from isSymbol set.
   */
  private[metals] def allPathsFor(
      source: AbsolutePath,
      isSymbol: Set[String],
  )(implicit ec: ExecutionContext): Future[Set[AbsolutePath]] = {
    buildTargets
      .inverseSourcesBspAll(source)
      .map(buildTargets =>
        pathsFor(buildTargets.toSet, isSymbol, includeStale = true).toSet
      )
  }

  private def references(
      source: AbsolutePath,
      params: ReferenceParams,
      snapshot: TextDocument,
      distance: TokenEditDistance,
      occ: SymbolOccurrence,
      alternatives: Set[String],
      isIncludeDeclaration: Boolean,
      findRealRange: AdjustRange,
      includeSynthetics: Synthetic => Boolean,
      supportsPcRefs: Boolean,
  ): Future[Seq[Location]] = {
    val isSymbol = alternatives + occ.symbol
    val isLocal = occ.symbol.isLocal
    if (isLocal && supportsPcRefs)
      compilers
        .references(params, EmptyCancelToken, findRealRange)
        .map(_.flatMap(_.locations))
    else {
      /* search local in the following cases:
       * - it's a dependency source.
       *   We can't search references inside dependencies so at least show them in a source file.
       * - it's a standalone file that doesn't belong to any build target
       */
      val searchLocal =
        source.isDependencySource(workspace) ||
          buildTargets.inverseSources(source).isEmpty ||
          isLocal

      val local =
        if (searchLocal)
          referenceLocations(
            snapshot,
            isSymbol,
            distance,
            params.getTextDocument.getUri,
            isIncludeDeclaration,
            findRealRange,
            includeSynthetics,
            source.isJava,
          )
        else Seq.empty

      def sourceContainsDefinition =
        occ.role.isDefinition || snapshot.symbols.exists(
          _.symbol == occ.symbol
        )
      val workspaceRefs =
        if (isLocal) Seq.empty
        else
          workspaceReferences(
            source,
            isSymbol,
            isIncludeDeclaration,
            findRealRange,
            includeSynthetics,
            sourceContainsDefinition,
          )
      Future.successful(local ++ workspaceRefs)
    }
  }

  private def referenceLocations(
      snapshot: TextDocument,
      isSymbol: Set[String],
      distance: TokenEditDistance,
      uri: String,
      isIncludeDeclaration: Boolean,
      findRealRange: AdjustRange,
      includeSynthetics: Synthetic => Boolean,
      isJava: Boolean,
  ): Seq[Location] = {
    val buf = Set.newBuilder[Location]
    def add(range: s.Range): Unit = {
      val revised = distance.toRevised(range.startLine, range.startCharacter)
      val dirtyLocation = range.toLocation(uri)
      for {
        location <- revised.toLocation(dirtyLocation)
      } {
        buf += location
      }
    }

    for {
      reference <- snapshot.occurrences
      if isSymbol(reference.symbol)
      if !reference.role.isDefinition || isIncludeDeclaration
      range <- reference.range.toList
    } {

      if (isJava) {
        add(range)
      }
      /* Find real range is used when renaming occurrences,
       * where we need to check if the symbol name matches exactly.
       * This was needed for some issues with macro annotations
       * and with renames we must be sure that a proper name is replaced.
       * In case of finding references, where false positives
       * are ok and speed is more important, we just use the default noAdjustRange.
       */
      else {
        findRealRange(range, snapshot.text, reference.symbol).foreach(add)
      }

    }

    for {
      synthetic <- snapshot.synthetics
      if Synthetics.existsSymbol(synthetic)(isSymbol) && includeSynthetics(
        synthetic
      )
      range <- synthetic.range.toList
    } add(range)

    buf.result().toSeq
  }

}

class SymbolAlternatives(symbol: String, name: String) {

  // Returns true if `info` is the companion object matching the occurrence class symbol.
  def isCompanionObject(info: SymbolInformation): Boolean =
    info.isObject &&
      info.displayName == name &&
      symbol == Symbols.Global(
        info.symbol.owner,
        Descriptor.Type(info.displayName),
      )

  // Returns true if `info` is the java constructor matching the occurrence class symbol.
  def isJavaConstructor(info: SymbolInformation): Boolean = {
    info.isConstructor &&
    (Symbol(info.symbol) match {
      case GlobalSymbol(clsSymbol, Descriptor.Method("<init>", _)) =>
        symbol == clsSymbol.value
      case _ =>
        false
    })
  }

  // Returns true if `info` is the companion class matching the occurrence object symbol.
  def isCompanionClass(info: SymbolInformation): Boolean = {
    info.isClass &&
    info.displayName == name &&
    symbol == Symbols.Global(
      info.symbol.owner,
      Descriptor.Term(info.displayName),
    )
  }

  // Returns true if `info` is a named parameter of the primary constructor
  def isContructorParam(info: SymbolInformation): Boolean = {
    info.isParameter &&
    info.displayName == name &&
    symbol == (Symbol(info.symbol) match {
      case GlobalSymbol(
            // This means it's the primary constructor
            GlobalSymbol(owner, Descriptor.Method("<init>", "()")),
            Descriptor.Parameter(_),
          ) =>
        Symbols.Global(owner.value, Descriptor.Term(name))
      case _ =>
        ""
    })
  }

  // Returns true if `info` is a field that corresponds to named parameter of the primary constructor
  def isFieldParam(info: SymbolInformation): Boolean = {
    (info.isVal || info.isVar) &&
    info.displayName == name &&
    symbol == (Symbol(info.symbol) match {
      case GlobalSymbol(owner, Descriptor.Term(name)) =>
        Symbols.Global(
          // This means it's the primary constructor
          Symbols.Global(owner.value, Descriptor.Method("<init>", "()")),
          Descriptor.Parameter(name),
        )
      case _ =>
        ""
    })
  }

  // Returns true if `info` is a parameter of a synthetic `copy` or `apply` matching the occurrence field symbol.
  def isCopyOrApplyParam(info: SymbolInformation): Boolean =
    info.isParameter &&
      info.displayName == name &&
      symbol == (Symbol(info.symbol) match {
        case GlobalSymbol(
              GlobalSymbol(
                GlobalSymbol(owner, Descriptor.Term(obj)),
                Descriptor.Method("apply", _),
              ),
              _,
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name),
          )
        case GlobalSymbol(
              GlobalSymbol(
                GlobalSymbol(owner, Descriptor.Type(obj)),
                Descriptor.Method("copy", _),
              ),
              _,
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name),
          )
        case _ =>
          ""
      })

  // Returns true if `info` is companion var setter method for occ.symbol var getter.
  def isVarSetter(info: SymbolInformation): Boolean =
    info.displayName.endsWith("_=") &&
      info.displayName.startsWith(name) &&
      symbol == (Symbol(info.symbol) match {
        case GlobalSymbol(owner, Descriptor.Method(setter, disambiguator)) =>
          Symbols.Global(
            owner.value,
            Descriptor.Method(setter.stripSuffix("_="), disambiguator),
          )
        case _ =>
          ""
      })
}

object SyntheticPackageObject {
  val regex: Regex = "/.*[$]package[.]".r
  def unapply(str: String): Option[String] =
    Option.when(regex.matches(str))(str)
}

trait AdjustRange {
  def apply(range: s.Range, text: String, symbol: String): Option[s.Range]
  def apply(loc: Location, text: String, symbol: String): Option[Location] = {
    val semRange = s.Range(
      loc.getRange().getStart().getLine(),
      loc.getRange().getStart().getCharacter(),
      loc.getRange().getEnd().getLine(),
      loc.getRange().getEnd().getCharacter(),
    )
    for (adjusted <- apply(semRange, text, symbol)) yield {
      loc.setRange(adjusted.toLsp)
      loc
    }
  }
}

object noAdjustRange extends AdjustRange {
  def apply(range: s.Range, text: String, symbol: String): Option[s.Range] =
    Some(range)
  override def apply(
      loc: Location,
      text: String,
      symbol: String,
  ): Option[Location] = Some(loc)
}

object AdjustRange {
  def apply(adjust: (s.Range, String, String) => Option[s.Range]): AdjustRange =
    new AdjustRange {
      def apply(range: s.Range, text: String, symbol: String): Option[s.Range] =
        adjust(range, text, symbol)
    }
}

class Lock {
  private val cancelPromise = Promise[Unit]
  private val completedPromise = Promise[Unit]

  def isCancelled = cancelPromise.isCompleted
  def complete(): Unit = completedPromise.trySuccess(())
  def cancelAndWaitUntilCompleted(): Future[Unit] = {
    cancelPromise.trySuccess(())
    completedPromise.future
  }
}

object Lock {
  val completed: Lock = {
    val lock = new Lock
    lock.complete()
    lock
  }
}
