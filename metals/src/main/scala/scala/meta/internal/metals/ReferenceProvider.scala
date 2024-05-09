package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
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
import scala.meta.internal.tokenizers.LegacyScanner
import scala.meta.internal.tokenizers.LegacyToken._
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

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
    extends SemanticdbFeatureProvider {
  val index: TrieMap[Path, IdentifierIndex.IndexEntry] = TrieMap.empty
  val identifierIndex: IdentifierIndex = new IdentifierIndex

  def addIdentifiers(file: AbsolutePath, set: Iterable[String]): Unit =
    buildTargets
      .inverseSources(file)
      .map(id => identifierIndex.addIdentifiers(file, id, set))

  def indexIdentifiers(
      path: AbsolutePath,
      text: String,
  ): Future[Unit] = Future {
    buildTargets.inverseSources(path).map { id =>
      val dialect = scalaVersionSelector.getDialect(path)
      val set = identifierIndex.collectIdentifiers(text, dialect)
      identifierIndex.addIdentifiers(path, id, set)
    }
  }

  override def reset(): Unit = {
    index.clear()
  }

  override def onDelete(file: AbsolutePath): Unit = {
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

      val entry = IdentifierIndex.IndexEntry(id, bloom)
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
    }
  }

  def referencesForWildcardImport(
      owner: String,
      source: AbsolutePath,
      directlyImportedSymbols: Set[String],
  ): List[String] = {
    def matchesOwner(symbol: String) = {
      symbol.startsWith(owner) && {
        val rest = symbol.stripPrefix(owner)
        rest match {
          case "" => true
          case "/package." => true
          case SyntheticPackageObject(_) => true
          case _ => false
        }
      }
    }
    semanticdbs().textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        doc.occurrences
          .map(_.symbol)
          .distinct
          .filter { s =>
            val reversedOwners = s.ownerChain.reverse

            (reversedOwners.length > 1
            && matchesOwner(reversedOwners(1))
            && !directlyImportedSymbols.contains(s))
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
    semanticdbs().textDocument(source).documentIncludingStale match {
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
                report.unsanitized.create(
                  Report(
                    "empty-references",
                    index
                      .map { case (path, entry) =>
                        s"$path -> ${entry.bloom.approximateElementCount()}"
                      }
                      .mkString("\n"),
                    s"Could not find any locations for ${result.occurrence}, printing index state",
                    Some(source.toString()),
                    Some(
                      source.toString() + ":" + result.occurrence.getOrElse("")
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
            path => !index.contains(path.toNIO),
          )

        Future
          .sequence(List(semanticdbResult, pcResult))
          .map(
            _.flatten
              .groupBy(_.symbol)
              .map { case (symbol, refs) =>
                ReferencesResult(symbol, refs.flatMap(_.locations))
              }
              .toList
          )
      case None =>
        scribe.debug(s"No semanticdb for $source")
        pcReferences(source, params).map(
          _.groupBy(_.symbol)
            .map { case (symbol, refs) =>
              ReferencesResult(symbol, refs.flatMap(_.locations))
            }
            .toList
        )
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
          if info.symbol != name
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
          isCandidate -- nonSyntheticSymbols ++ additionalAlternativesForSynthetic
        else
          isCandidate -- nonSyntheticSymbols
      case None => Set.empty
    }
  }

  private def pcReferences(
      path: AbsolutePath,
      params: ReferenceParams,
  ): Future[List[ReferencesResult]] = {
    compilers.references(params, EmptyCancelToken).flatMap { foundRefs =>
      pcReferences(
        path,
        foundRefs.map(_.symbol),
        includeDeclaration = params.getContext().isIncludeDeclaration(),
        filterTargetFiles = _ != path,
      ).map(_ ++ foundRefs)
    }
  }

  private def pcReferences(
      path: AbsolutePath,
      symbols: List[String],
      includeDeclaration: Boolean,
      filterTargetFiles: AbsolutePath => Boolean,
  ): Future[List[ReferencesResult]] = {
    val visited = mutable.Set[AbsolutePath]()
    val results = for {
      buildTarget <- buildTargets.inverseSources(path).toList
      _ = visited.clear()
      symbol <- symbols
      name = nameFromSymbol(symbol)
      searchFile <- pathsForName(buildTarget, name)
      if (filterTargetFiles(searchFile) && !visited(searchFile))
    } yield {
      visited += searchFile
      compilers.references(
        searchFile,
        includeDeclaration,
        symbol,
      )
    }
    Future.sequence(results).map(_.flatten)
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
  ): Iterator[AbsolutePath] = {
    val allowedBuildTargets = buildTargets.allInverseDependencies(buildTarget)
    val visited = scala.collection.mutable.Set.empty[AbsolutePath]
    for {
      (path, entry) <- identifierIndex.index.iterator
      if allowedBuildTargets.contains(entry.id) &&
        entry.bloom.mightContain(name)
      sourcePath = AbsolutePath(path)
      if !visited(sourcePath)
      _ = visited.add(sourcePath)
      if sourcePath.exists
    } yield sourcePath
  }

  /**
   * Return all paths to files which contain at least one symbol from isSymbol set.
   */
  private def pathsFor(
      buildTargetSet: Set[BuildTargetIdentifier],
      isSymbol: Set[String],
  ): Iterator[AbsolutePath] = {
    if (buildTargetSet.isEmpty) Iterator.empty
    else {
      val allowedBuildTargets =
        buildTargetSet.flatMap(buildTargets.allInverseDependencies)
      val visited = scala.collection.mutable.Set.empty[AbsolutePath]
      val result = for {
        (path, entry) <- index.iterator
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
          isSymbol
            .flatMap { sym =>
              definition.destinationProvider
                .definition(sym, Some(source))
                .map(_.path)
            }

        if (foundDefinitionLocations.isEmpty) Set(source)
        else foundDefinitionLocations
      }

    val definitionBuildTargets =
      definitionPaths.flatMap { path =>
        buildTargets.inverseSourcesAll(path).toSet
      }

    val result = for {
      sourcePath <- pathsFor(definitionBuildTargets, isSymbol)
      semanticdb <-
        semanticdbs()
          .textDocument(sourcePath)
          .documentIncludingStale
          .iterator
      semanticdbDistance = buffers.tokenEditDistance(
        sourcePath,
        semanticdb.text,
        trees,
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
      .map(buildTargets => pathsFor(buildTargets.toSet, isSymbol).toSet)
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
  ): Future[Seq[Location]] = {
    val isSymbol = alternatives + occ.symbol
    val isLocal = occ.symbol.isLocal
    if (isLocal)
      compilers
        .references(params, EmptyCancelToken)
        .map(_.flatMap(_.locations))
    else {
      /* search local in the following cases:
       * - it's a dependency source.
       *   We can't search references inside dependencies so at least show them in a source file.
       * - it's a standalone file that doesn't belong to any build target
       */
      val searchLocal =
        source.isDependencySource(workspace) ||
          buildTargets.inverseSources(source).isEmpty

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

      val sourceContainsDefinition =
        occ.role.isDefinition || snapshot.symbols.exists(
          _.symbol == occ.symbol
        )
      val workspaceRefs =
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

    buf.result().toSeq.sortWith(sortByLocationPosition)
  }

  private def sortByLocationPosition(l1: Location, l2: Location): Boolean = {
    l1.getRange.getStart.getLine < l2.getRange.getStart.getLine
  }

  private val noAdjustRange: AdjustRange =
    (range: s.Range, _: String, _: String) => Some(range)
  type AdjustRange = (s.Range, String, String) => Option[s.Range]

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
