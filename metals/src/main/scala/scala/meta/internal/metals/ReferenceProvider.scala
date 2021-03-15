package scala.meta.internal.metals

import java.nio.charset.StandardCharsets
import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.DefinitionAlternatives.GlobalSymbol
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams

final class ReferenceProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffers: Buffers,
    definition: DefinitionProvider,
    remote: RemoteLanguageServer,
    trees: Trees
) {
  private var referencedPackages: BloomFilter[CharSequence] =
    BloomFilters.create(10000)
  val index: TrieMap[Path, BloomFilter[CharSequence]] =
    TrieMap.empty[Path, BloomFilter[CharSequence]]

  def reset(): Unit = {
    index.clear()
  }
  def onDelete(file: Path): Unit = {
    index.remove(file)
  }

  def onChange(docs: TextDocuments, file: AbsolutePath): Unit = {
    val count = docs.documents.foldLeft(0)(_ + _.occurrences.length)
    val syntheticsCount = docs.documents.foldLeft(0)(_ + _.synthetics.length)
    val bloom = BloomFilter.create(
      Funnels.stringFunnel(StandardCharsets.UTF_8),
      Integer.valueOf((count + syntheticsCount) * 2),
      0.01
    )
    index(file.toNIO) = bloom
    docs.documents.foreach { d =>
      d.occurrences.foreach { o =>
        if (o.symbol.endsWith("/")) {
          referencedPackages.put(o.symbol)
        }
        bloom.put(o.symbol)
      }
      d.synthetics.foreach { synthetic =>
        Synthetics.foreachSymbol(synthetic) { sym =>
          bloom.put(sym)
          Synthetics.Continue
        }
      }
    }
    resizeReferencedPackages()
  }

  def references(
      params: ReferenceParams,
      findRealRange: AdjustRange = noAdjustRange,
      includeSynthetics: Synthetic => Boolean = _ => true
  ): ReferencesResult = {
    val source = params.getTextDocument.getUri.toAbsolutePath
    semanticdbs.textDocument(source).documentIncludingStale match {
      case Some(doc) =>
        val ResolvedSymbolOccurrence(distance, maybeOccurrence) =
          definition.positionOccurrence(source, params.getPosition, doc)
        maybeOccurrence match {
          case Some(occurrence) =>
            val alternatives = referenceAlternatives(occurrence.symbol, doc)
            val locations = references(
              source,
              params,
              doc,
              distance,
              occurrence,
              alternatives,
              params.getContext.isIncludeDeclaration,
              findRealRange,
              includeSynthetics
            )
            ReferencesResult(occurrence.symbol, locations)
          case None =>
            ReferencesResult.empty
        }
      case None =>
        // NOTE(olafur): we block here instead of returning a Future because it
        // requires a significant refactoring to make the reference provider and
        // its dependencies (including rename provider) asynchronous. The remote
        // language server returns `Future.successful(None)` when it's disabled
        // so this isn't even blocking for normal usage of Metals.
        remote.referencesBlocking(params).getOrElse(ReferencesResult.empty)
    }
  }

  // Returns alternatives symbols for which "goto definition" resolves to the occurrence symbol.
  private def referenceAlternatives(
      symbol: String,
      referenceDoc: TextDocument
  ): Set[String] = {
    val definitionDoc = if (referenceDoc.symbols.exists(_.symbol == symbol)) {
      Some(referenceDoc)
    } else {
      for {
        location <- definition.fromSymbol(symbol).asScala.headOption
        source = location.getUri().toAbsolutePath
        definitionDoc <- semanticdbs.textDocument(source).documentIncludingStale
      } yield definitionDoc
    }

    definitionDoc match {
      case Some(definitionDoc) =>
        val name = symbol.desc.name.value
        val alternatives = new SymbolAlternatives(symbol, name)

        val candidates = for {
          info <- definitionDoc.symbols
          if info.symbol != name
          if {
            alternatives.isVarSetter(info) ||
            alternatives.isCompanionObject(info) ||
            alternatives.isCopyOrApplyParam(info) ||
            alternatives.isContructorParam(info)
          }
        } yield info.symbol

        val isCandidate = candidates.toSet

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

        if (isSyntheticSymbol)
          isCandidate -- nonSyntheticSymbols ++ additionalAlternativesForSynthetic
        else
          isCandidate -- nonSyntheticSymbols
      case None => Set.empty
    }
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
      includeSynthetics: Synthetic => Boolean
  ): Seq[Location] = {
    val isSymbol = alternatives + occ.symbol
    if (occ.symbol.isLocal || source.isDependencySource(workspace)) {
      referenceLocations(
        snapshot,
        isSymbol,
        distance,
        params.getTextDocument.getUri,
        isIncludeDeclaration,
        findRealRange,
        includeSynthetics
      )
    } else {
      val visited = scala.collection.mutable.Set.empty[AbsolutePath]
      val results: Iterator[Location] = for {
        (path, bloom) <- index.iterator
        if isSymbol.exists(bloom.mightContain)
        scalaPath = AbsolutePath(path)
        if !visited(scalaPath)
        _ = visited.add(scalaPath)
        if scalaPath.exists
        semanticdb <-
          semanticdbs
            .textDocument(scalaPath)
            .documentIncludingStale
            .iterator
        semanticdbDistance = buffers.tokenEditDistance(
          scalaPath,
          semanticdb.text,
          trees
        )
        uri = scalaPath.toURI.toString
        reference <-
          try {
            referenceLocations(
              semanticdb,
              isSymbol,
              semanticdbDistance,
              uri,
              isIncludeDeclaration,
              findRealRange,
              includeSynthetics
            )
          } catch {
            case NonFatal(e) =>
              // Can happen for example if the SemanticDB text is empty for some reason.
              scribe.error(s"reference: $scalaPath", e)
              Nil
          }
      } yield reference
      results.toSeq
    }
  }

  private def referenceLocations(
      snapshot: TextDocument,
      isSymbol: Set[String],
      distance: TokenEditDistance,
      uri: String,
      isIncludeDeclaration: Boolean,
      findRealRange: AdjustRange,
      includeSynthetics: Synthetic => Boolean
  ): Seq[Location] = {
    val buf = Seq.newBuilder[Location]
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

      /* Find real range is used when renaming occurrences,
       * where we need to check if the symbol name matches exactly.
       * This was needed for some issues with macro annotations
       * and with renames we must be sure that a proper name is replaced.
       * In case of finding references, where false positives
       * are ok and speed is more important, we just use the default noAdjustRange.
       */
      findRealRange(range, snapshot.text, reference.symbol).foreach(add)
    }

    for {
      synthetic <- snapshot.synthetics
      if Synthetics.existsSymbol(synthetic)(isSymbol) && includeSynthetics(
        synthetic
      )
      range <- synthetic.range.toList
    } add(range)

    buf.result().sortWith(sortByLocationPosition)
  }

  private def sortByLocationPosition(l1: Location, l2: Location): Boolean = {
    l1.getRange.getStart.getLine < l2.getRange.getStart.getLine
  }

  private val noAdjustRange: AdjustRange =
    (range: s.Range, text: String, symbol: String) => Some(range)
  type AdjustRange = (s.Range, String, String) => Option[s.Range]

  private def resizeReferencedPackages(): Unit = {
    // Increase the size of the set of referenced packages if the false positive ratio is too high.
    if (referencedPackages.expectedFpp() > 0.05) {
      referencedPackages =
        BloomFilters.create(referencedPackages.approximateElementCount() * 2)
    }
  }

}

class SymbolAlternatives(symbol: String, name: String) {

  // Returns true if `info` is the companion object matching the occurrence class symbol.
  def isCompanionObject(info: SymbolInformation): Boolean =
    info.isObject &&
      info.displayName == name &&
      symbol == Symbols.Global(
        info.symbol.owner,
        Descriptor.Type(info.displayName)
      )

  // Returns true if `info` is the companion class matching the occurrence object symbol.
  def isCompanionClass(info: SymbolInformation): Boolean = {
    info.isClass &&
    info.displayName == name &&
    symbol == Symbols.Global(
      info.symbol.owner,
      Descriptor.Term(info.displayName)
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
            Descriptor.Parameter(_)
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
          Descriptor.Parameter(name)
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
                Descriptor.Method("apply", _)
              ),
              _
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name)
          )
        case GlobalSymbol(
              GlobalSymbol(
                GlobalSymbol(owner, Descriptor.Type(obj)),
                Descriptor.Method("copy", _)
              ),
              _
            ) =>
          Symbols.Global(
            Symbols.Global(owner.value, Descriptor.Type(obj)),
            Descriptor.Term(name)
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
            Descriptor.Method(setter.stripSuffix("_="), disambiguator)
          )
        case _ =>
          ""
      })
}
