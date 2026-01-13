package scala.meta.internal.metals.mbt

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.Buffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReferencesResult
import scala.meta.internal.metals.SymbolAlternatives
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.noAdjustRange
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.XtensionSemanticdbSymbolInformation
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.{lsp4j => l}

class MbtReferenceProvider(
    mbt: MbtWorkspaceSymbolProvider,
    compilers: Compilers,
    buffers: Buffers,
    time: Time,
    languageClient: MetalsLanguageClient,
    workDoneProgress: WorkDoneProgress,
)(implicit ec: ExecutionContext) {
  private val cache = new TextDocumentCache()

  // When looking for usages of a method, we don't visit supermethods from these
  // types because it would result in a ton of noisy results. This list should
  // not enumerate ad-hoc cases like `java.io.Serializable`, we should only
  // enumerate cases where it will confuse the user if they get matching
  // results. These types are automatically made parents of all classes and
  // Scala case classes so people don't even realize their type if extending,
  // for example, `scala.Product`.
  private val ignoredSuperSymbols = Set(
    "java/lang/Object#", "scala/Any#", "scala/AnyRef#", "scala/Product#",
    "scala/Serializable#",
  )

  private val groupSize = 100

  /** Returns the length of the common path prefix between two paths. */
  private def commonPrefixLength(a: AbsolutePath, b: AbsolutePath): Int = {
    val aStr = a.toString
    val bStr = b.toString
    var i = 0
    val minLen = math.min(aStr.length, bStr.length)
    while (i < minLen && aStr.charAt(i) == bStr.charAt(i)) {
      i += 1
    }
    i
  }
  // We timebox the search because the user could do "Find References" on
  // java.lang.String and get matches in ALL files, which is always going to
  // take a long time to compute. We still stream results for clients that
  // support partial results so the user should see results much faster than
  // this timeout.
  private val timeout: FiniteDuration =
    if (sys.props.contains("metals.debug")) 20.minutes else 20.seconds
  def implementations(
      params: l.TextDocumentPositionParams
  ): Future[List[l.Location]] =
    workDoneProgress.trackProgressFuture(
      "Finding implementations",
      taskProgress =>
        Future {
          doImplementations(params, taskProgress)
        },
    )

  private def doImplementations(
      params: l.TextDocumentPositionParams,
      taskProgress: TaskProgress,
  ): List[l.Location] = {
    val timer = new Timer(time)
    val path = params.getTextDocument.getUri.toAbsolutePath
    val pos = params.getPosition()
    val requestDoc = cache.indexSingle(path)
    val enclosingOccurrences = this.enclosingOccurrences(requestDoc, pos)
    scribe.info(
      s"implementations: found ${enclosingOccurrences.length} occurrences"
    )
    val enclosingSymbols = enclosingOccurrences.map(_.symbol)
    val isOverridenSymbol = mutable.Set.from(enclosingSymbols)
    val isVisitedMethodName = mutable.Set.empty[String]
    var lastQueryRound = Set.empty[String]
    val result = mutable.ListBuffer.empty[l.Location]
    val isVisitedURI = mutable.Set.empty[String]
    var visited = 0

    def visitDoc(doc: s.TextDocument): Boolean = {
      visited += 1

      def overridesOrImplements(info: s.SymbolInformation): Boolean = {
        if (
          info.language.isScala
          && (info.kind.isClass || info.kind.isTrait || info.kind.isObject)
        ) {
          info.signature match {
            case s.ClassSignature(_, parents, _, _) =>
              parents.exists {
                case TypeRef(_, symbol, _) => isOverridenSymbol(symbol)
                case _ => false
              }
            case _ => false
          }
        } else {
          info.overriddenSymbols.exists(sym => isOverridenSymbol(sym))
        }
      }

      if (isVisitedURI.contains(doc.uri)) {
        return false
      }
      isVisitedURI += doc.uri
      for {
        info <- doc.symbols.iterator
        if overridesOrImplements(info)
        occ <- doc.occurrences.iterator
        if occ.symbol == info.symbol && occ.role.isDefinition
        range <- occ.range.toList
      } {
        if (info.kind.isMethod) {
          isVisitedMethodName += info.displayName
        }
        isOverridenSymbol += occ.symbol
        result += range.toLocation(doc.uri)
      }
      true
    }
    val maxDepth = 10
    @tailrec
    def loop(depth: Int): Unit = {
      if (depth > maxDepth) {
        scribe.warn(s"implementation: depth reached: $depth")
        return
      }
      val toQuery = isOverridenSymbol.toSeq.filterNot { s =>
        val sym = Symbol(s)
        if (sym.isMethod) isVisitedMethodName.contains(sym.displayName)
        else lastQueryRound.contains(s)
      }
      val candidates = mbt
        .possibleReferences(
          MbtPossibleReferencesParams(implementations = toQuery)
        )
        .toSeq
        .sortBy(c => -commonPrefixLength(path, c))
      scribe.info(s"Found ${candidates.size} candidate files.")
      lastQueryRound = Set.from(isOverridenSymbol)
      var didMakeProgress = false
      var processedInRound = 0
      val totalInRound = candidates.size
      for {
        paths <- candidates.iterator.grouped(groupSize)
        if !timer.hasElapsed(timeout)
        doc <- cache.index(paths).documents
      } {
        val didVisit = visitDoc(doc)
        didMakeProgress = didMakeProgress || didVisit
        processedInRound += 1
        taskProgress.update(
          processedInRound,
          totalInRound,
          Some(s"Processing ${doc.uri.toString.split("/").last}"),
        )
      }
      if (timer.hasElapsed(timeout)) {
        scribe.warn(
          s"Time out analyzing candidate files at $visited/${candidates.size}."
        )
        taskProgress.update(
          processedInRound,
          totalInRound,
          Some("Time out analyzing candidate files"),
        )
      }
      if (didMakeProgress) {
        loop(depth = depth + 1)
      }
    }

    // visitDoc(requestDoc)
    loop(depth = 0)
    result.toList
  }

  def references(params: ReferenceParams): Future[List[ReferencesResult]] = {
    val timer = new Timer(time)
    val path = params.getTextDocument.getUri.toAbsolutePath
    val queryRange = params.getPosition()
    val requestDoc = cache.indexSingle(path)
    val enclosingOccurrences =
      this.enclosingOccurrences(requestDoc, queryRange)
    scribe.info(
      s"references: found ${enclosingOccurrences.length} occurrences"
    )

    // If all symbols are local, use compilers.references directly
    val allLocal = enclosingOccurrences.forall(_.symbol.isLocal)

    if (allLocal && requestDoc.language.isScala) {
      scribe.info("references: all local, using compilers.references")
      compilers
        .references(params, EmptyCancelToken, noAdjustRange)
    } else {
      workDoneProgress.trackProgressFuture(
        "Finding references",
        taskProgress =>
          Future {
            doReferences(
              timer,
              params,
              requestDoc,
              enclosingOccurrences,
              taskProgress,
            )
          },
      )
    }
  }

  private def doReferences(
      timer: Timer,
      params: ReferenceParams,
      requestDoc: s.TextDocument,
      enclosingOccurrences: Seq[s.SymbolOccurrence],
      taskProgress: TaskProgress,
  ): List[ReferencesResult] = {
    val token = params.getPartialResultToken()
    val superMethods = for {
      enclosing <- enclosingOccurrences
      info <- requestDoc.symbols.find(_.symbol == enclosing.symbol).iterator
      if info.kind.isMethod
      canBeOverridden = info.displayName != "<init>" && !info.isFinal
      selfSymbol =
        if (canBeOverridden)
          // Search for overrides of this method
          Iterator.single(info.symbol)
        else Iterator.empty
      overridden <- selfSymbol ++ info.overriddenSymbols.iterator
      if !ignoredSuperSymbols(overridden)
    } yield overridden
    val implementationMethods =
      (findImplementations(
        timer,
        // Use maximum half of the timeout for finding implementations
        timeout.div(2),
        cache,
        superMethods,
      ) ++ superMethods).distinct
    val toQuerySymbols =
      if (implementationMethods.nonEmpty) implementationMethods
      else enclosingOccurrences.map(_.symbol)
    // Expand symbols to include alternatives like companion objects/classes,
    // apply/copy params, constructor params, etc.
    val scalaMatchingOccurrence = (for {
      symbol <- toQuerySymbols.iterator
      docAlternatives = SymbolAlternatives.referenceAlternatives(
        symbol,
        requestDoc,
      )
      alternative <- docAlternatives + symbol
    } yield alternative -> symbol).toMap

    val javaMatchingOccurrence = (for {
      symbol <- toQuerySymbols.iterator
      expanded = SymbolAlternatives.expand(Symbol(symbol))
      alternative <- expanded
    } yield alternative -> symbol).toMap

    val enclosingGlobalOccurrences =
      toQuerySymbols.filter(sym => sym.isGlobal)
    val externalDocumentCandidates: Iterable[AbsolutePath] =
      if (enclosingGlobalOccurrences.nonEmpty) {
        mbt.possibleReferences(
          MbtPossibleReferencesParams(references = toQuerySymbols)
        )
      } else {
        Nil
      }
    val candidatesList = externalDocumentCandidates.iterator.distinct.toSeq
    val totalCandidates = candidatesList.size
    scribe.info(
      s"references: found $totalCandidates external document candidates in $timer"
    )
    val referenceResults = toQuerySymbols.iterator
      .map(occ => occ -> Buffer.empty[l.Location])
      .toMap
    var processedCandidates = 0

    def processDoc(doc: s.TextDocument): Unit = {
      for {
        occ <- doc.occurrences
        if occ.symbol.isGlobal || doc.eq(requestDoc)
        range <- occ.range.toList
        matchSymbol <-
          if (doc.language.isScala) scalaMatchingOccurrence.get(occ.symbol)
          else javaMatchingOccurrence.get(occ.symbol)
        // Exclude definition occurrences for alternate symbols. For example, when
        // doing find-refs on a class symbol, we want usages of the class
        // constructor but not definitions of those constructors.
        if occ.role.isReference || occ.symbol == matchSymbol
        x <- referenceResults.get(matchSymbol)
      } {
        val location = range.toLocation(doc.uri)
        if (token == null) {
          x += location
        } else {
          languageClient.notifyProgress(
            new l.ProgressParams(token, JEither.forRight(location))
          )
        }
      }
    }

    // Process the request document first
    processDoc(requestDoc)

    // Process external candidates with progress reporting
    for {
      candidates <- candidatesList.iterator.grouped(groupSize)
      if !timer.hasElapsed(timeout)
    } {
      val docTimer = new Timer(time)
      val docs = cache.index(candidates).documents
      scribe.info(
        s"references: indexed ${candidates.length} candidates in $docTimer"
      )
      docs.foreach(processDoc)
      processedCandidates += candidates.length
      taskProgress.update(processedCandidates, totalCandidates)
    }
    if (timer.hasElapsed(timeout)) {
      taskProgress.update(
        processedCandidates,
        totalCandidates,
        Some("Time out analyzing candidate files"),
      )
    }
    val resultCount = referenceResults.valuesIterator.map(_.size).sum
    scribe.info(
      s"references: found $resultCount reference results in $timer"
    )
    referenceResults.iterator.map { case (symbol, locations) =>
      ReferencesResult(symbol, locations.toSeq)
    }.toList
  }

  private def enclosingOccurrences(
      requestDoc: s.TextDocument,
      pos: l.Position,
  ): Seq[s.SymbolOccurrence] = {
    val enclosingOccurrencesOriginal = for {
      occ <- requestDoc.occurrences
      range <- occ.range.toList
      if range.encloses(pos)
    } yield occ
    if (enclosingOccurrencesOriginal.exists(_.role.isDefinition))
      // HACK: only show references to the definition *if* there is at least
      // one definition enclosing the range. This prevents unwanted behavior
      // where, for example, a Java enum memeber declares a symbol (the enum
      // member) and also has a synthetic reference to the class constructor
      // (which we don't want to find usages of). Ideally, semanticdb-javac
      // wouldn't emit the synthetic reference so this hack isn't needed.
      enclosingOccurrencesOriginal.filter(_.role.isDefinition)
    else enclosingOccurrencesOriginal
  }

  private def findImplementations(
      timer: Timer,
      timeout: FiniteDuration,
      cache: TextDocumentCache,
      overriddenSymbols: Seq[String],
  ): Seq[String] = {
    val candidates = mbt.possibleReferences(
      MbtPossibleReferencesParams(implementations = overriddenSymbols)
    )
    if (candidates.isEmpty) {
      return Nil
    }
    val isOverridenSymbol = overriddenSymbols.toSet
    (for {
      paths <- candidates.iterator.grouped(groupSize)
      if !timer.hasElapsed(timeout)
      doc <- cache.index(paths).documents
      info <- doc.symbols.iterator
      overrideSymbol <- info.overriddenSymbols.iterator
      if isOverridenSymbol(overrideSymbol)
    } yield info.symbol).toSeq
  }

  // NOTE: this cache is similar to InteractiveSemanticdbs and it makes sense to
  // extract it out to a reused class. InteractiveSemanticdbs is used in a lot
  // of places in the codebase and it doesn't support the batch indexing that
  // find-refs needs to get acceptable performance. For time being, it's fine to
  // keep this in-memory cache here. Ideally, when we build the shared
  // abstraction, it will also be powered by a persistent cache, and it's able
  // to deal with adjusting positions in stale payloads, etc.
  private class TextDocumentCache() {
    private case class CacheKey(path: AbsolutePath, fileSize: Long)
    private val cache = TrieMap.empty[AbsolutePath, s.TextDocument]
    def indexSingle(path: AbsolutePath): s.TextDocument = {
      index(Seq(path)).documents.headOption.getOrElse {
        scribe.warn(s"references: no document found for $path")
        s.TextDocument()
      }
    }
    def index(paths: Seq[AbsolutePath]): s.TextDocuments = {
      val docs = Buffer.empty[s.TextDocument]
      val toIndex = paths.filter { path =>
        val input = path.toInputFromBuffers(buffers)
        val md5 = MD5.compute(input.text)
        cache.get(path) match {
          case Some(doc) if doc.md5 == md5 =>
            docs += doc
            false
          case _ =>
            true
        }
      }
      if (toIndex.isEmpty) {
        s.TextDocuments(documents = docs.toSeq)
      } else {
        val result = Await
          .result(
            compilers.batchSemanticdbTextDocuments(toIndex, EmptyCancelToken),
            timeout,
          )
          .documents
        result.foreach { doc =>
          cache.put(doc.uri.toAbsolutePath, doc)
        }
        docs ++= result
        s.TextDocuments(documents = docs.toSeq)
      }
    }
  }

}
