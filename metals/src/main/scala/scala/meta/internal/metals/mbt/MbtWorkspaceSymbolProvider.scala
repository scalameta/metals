package scala.meta.internal.metals.mbt

import java.io.BufferedOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.{util => ju}
import javax.tools.JavaFileManager
import javax.tools.StandardJavaFileManager

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashSet
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.infra.Event
import scala.meta.infra.MonitoringClient
import scala.meta.inputs.Input
import scala.meta.internal.infra.NoopMonitoringClient
import scala.meta.internal.jmbt.Mbt
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.BaseFallbackClasspaths
import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Configs.JavaSymbolLoaderConfig
import scala.meta.internal.metals.Configs.TurbineRecompileDelayConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.EmptyFallbackClasspaths
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.FingerprintedCharSequence
import scala.meta.internal.metals.LoggerReportContext
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Sleeper
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.tokenizers.UnexpectedInputEndException
import scala.meta.io.AbsolutePath
import scala.meta.pc
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import com.google.turbine.diag.SourceFile
import org.eclipse.{lsp4j => l}

case class MbtWorkspaceSymbolSearchParams(
    query: String,
    buildTargetIdentifier: String,
)

case class MbtPossibleReferencesParams(
    references: collection.Seq[String] = Nil,
    implementations: collection.Seq[String] = Nil,
)

object MbtWorkspaceSymbolProvider {
  def isRelevantPath(file: String): Boolean = {
    file.endsWith(".java") ||
    file.endsWith(".proto") ||
    file.endsWith(".scala")
  }
  def forTesting(): MbtWorkspaceSymbolProvider = {
    val tmp = Files.createTempDirectory("mbt-workspace-symbol-provider")
    tmp.toFile().deleteOnExit()
    new MbtWorkspaceSymbolProvider(AbsolutePath(tmp))
  }
}

class MbtWorkspaceSymbolProvider(
    val workspace: AbsolutePath,
    config: () => WorkspaceSymbolProviderConfig = () =>
      WorkspaceSymbolProviderConfig.mbt,
    buffers: Buffers = Buffers(),
    time: Time = Time.system,
    metrics: MonitoringClient = new NoopMonitoringClient(),
    mtags: () => Mtags = () => Mtags.testingSingleton,
    progress: BaseWorkDoneProgress = EmptyWorkDoneProgress,
    onIndexingDone: () => Unit = () => (),
    javaSymbolLoader: () => JavaSymbolLoaderConfig = () =>
      JavaSymbolLoaderConfig.default,
    fallbackClasspaths: () => BaseFallbackClasspaths = () =>
      EmptyFallbackClasspaths,
    sleeper: Sleeper = Sleeper.TestingSleeper,
    turbineRecompileDelay: () => TurbineRecompileDelayConfig = () =>
      TurbineRecompileDelayConfig.fromConfig(None),
)(implicit
    val ec: ExecutionContext = ExecutionContext.Implicits.global,
    val rc: ReportContext = LoggerReportContext,
) extends SemanticdbFileManager
    with JavaFileManagerFactory {

  private val indexFile: AbsolutePath = workspace.resolve(".metals/index.mbt")
  private val isIndexing: AtomicBoolean = new AtomicBoolean(false)
  private val turbineCompiler = new TurbineCompiler[AbsolutePath](
    () => documentsKeys,
    file =>
      if (!file.toLanguage.isJava) None
      else toInput(file).map(input => new SourceFile(input.path, input.text)),
    () => fallbackClasspaths().javaCompilerClasspath(),
    progress,
    // We don't need to re-compile the workspace super regularly because we can
    // load recently changed files from the sourcepath.
    turbineRecompileDelay().duration,
    sleeper = sleeper,
    onIndexingDone = onIndexingDone,
  )

  // NOTE: runs unconditionally even if the user config is not mbt-v2 for usage
  // in MetalsLspService.onUserConfigUpdate
  def recompileTurbineClasspath(): Future[Unit] = {
    turbineCompiler.compileNow().ignoreValue
  }

  def close(): Unit = {}

  // The source of truth for what files belong to the workspace, and their attached indexed data.
  // DO NOT update this map directly since have a couple derivative collections.
  // Instead, use the following methods to update the index:
  // - onDidChange(file: AbsolutePath): Unit
  // - onDidDelete(file: AbsolutePath): Unit
  // - onDidChangeSymbols(params: OnDidChangeSymbolsParams): Unit
  private lazy val documents: TrieMap[AbsolutePath, IndexedDocument] =
    readIndex()
  def readInitialIndexForTestingPurposes(): Unit = {
    // Just trigger the lazy val initialization without doing other expensive
    // work that is not necessary for tests or benchmarks.
    documents.size
  }
  // `documentsKeys` is effectively `documents.keys.par` but without the
  // overhead to copy the keys into a parallel collection at query time.  Make
  // sure to call updateDocumentsKeys() when you add or remove a document.
  @volatile private var documentsKeys = ParArray.empty[AbsolutePath]

  // Maps SemanticDB package symbol (for example, "scala/collection/") to all
  // the files that directly belong to that package. This index powers repo-wide
  // -sourcepath imports for JavaPruneCompilerFileManager. It's important to manually
  private val documentsByPackage: TrieMap[String, ConcurrentSkipListSet[Path]] =
    TrieMap.empty[String, ju.concurrent.ConcurrentSkipListSet[Path]]

  def onReindex(): IndexingStats = try {
    if (isIndexing.compareAndSet(false, true)) {
      onReindexInternal()
    } else {
      scribe.warn(
        "mbt-v2: already indexing workspace symbols, skipping reindex"
      )
      IndexingStats.empty
    }
  } catch {
    case NonFatal(e) =>
      scribe.error(s"mbt-v2: error reindexing workspace symbols", e)
      IndexingStats.empty
  } finally {
    isIndexing.set(false)
  }

  private def onReindexInternal(): IndexingStats = {
    if (!config().isMBT) {
      scribe.warn(s"mbt-v2: config is not mbt-v2, skipping reindex")
      return IndexingStats.empty
    }

    val timer = new Timer(time)
    // Step 1: list all files in HEAD and include OIDs.
    val files = GitVCS.lsFilesStage(workspace)
    if (files.isEmpty) {
      // A more detailed error message is logged if GitVCS.lsFilesStage fails.
      return IndexingStats.empty
    }

    // Step 2: filter down what files in the git repo are missing results in the
    // index.
    val toIndex = ParArray.fromSpecific(for {
      file <- files
      path = workspace.resolve(file.path)
      isCached = documents.get(path).exists(_.oid == file.oid)
      if !isCached
    } yield path)
    if (toIndex.nonEmpty) {
      scribe.info(s"mbt-v2: indexing ${toIndex.length} files")
    }

    val (task, token) = progress.startProgress(
      message = "Indexing workspace symbols",
      withProgress = true,
      showTimer = true,
      onCancel = None,
    )
    try {
      task.maybeProgress.foreach(_.update(0, toIndex.length))

      val indexedFilesCount = new AtomicInteger()
      // Step 3: The actual indexing, happens in parallel. Treat these as regular
      // didChange events for each individual file.
      toIndex.foreach { file =>
        try {
          onDidChangeInternal(file, updateDocumentKeys = false)
          val count = indexedFilesCount.incrementAndGet()
          if (count % 50 == 0) {
            task.maybeProgress.foreach(_.update(count, toIndex.length))
          }
        } catch {
          case NonFatal(e) =>
            scribe.error(s"mbt-v2: error indexing file ${file}", e)
          case _: StackOverflowError =>
            scribe.error(s"mbt-v2: stack overflow indexing file ${file}")
        }
      }
      updateDocumentsKeys(documents)
    } finally {

      val end = new l.WorkDoneProgressEnd()
      end.setMessage(s"done in $timer")
      progress.endProgress(token)
      onIndexingDone()
    }

    // Step 4: Write the index to disk. It's technically fine to move writing
    // the index to a background job.  Might be worth doing someday.
    writeIndex()

    // Step 5: record metrics.
    // This metric includes everything to create an up-todate index including
    // - File I/O to read the index from disk
    // - Running `git ls-files --stage`
    // - Parsing and indexing all changed files
    // - File I/O to write the index to disk.
    metrics.recordEvent(
      Event.duration("mbt2_index_workspace_symbol", timer.elapsed)
    )
    scribe.info(
      f"time: mbt-v2 loaded index for ${documents.size} files in ${timer}"
    )

    IndexingStats(
      files.length,
      toIndex.length,
      backgroundJobs = Future
        .sequence(
          Seq(
            Future {
              // We intentionally exclude `git status` from the metrics because this command
              // can take a very long time to run in large repos and it's not something we
              // can optimize.
              synchronizeWithGitStatus()
            },
            if (javaSymbolLoader().isTurbineClasspath) {
              turbineCompiler.compileNow()
            } else {
              Future.unit
            },
          )
        )
        .ignoreValue,
    )
  }

  private def synchronizeWithGitStatus(): Unit = {
    GitVCS
      .status(workspace)
      .foreach(file => this.onDidChange(file.file))
  }

  def onDidChangeSymbols(
      params: OnDidChangeSymbolsParams
  ): Future[Unit] = {
    val indexedDoc = IndexedDocument.fromOnDidChangeParams(params)
    putDocument(params.path, indexedDoc, updateDocumentKeys = true)
  }
  def onDidDelete(file: AbsolutePath): Future[Unit] = {
    documents.remove(file) match {
      case None => Future.unit
      case Some(doc) =>
        updateDocumentsKeys(documents)
        // Remove from package index
        for {
          pkg <- doc.semanticdbPackages
          files <- documentsByPackage.get(pkg)
        } {
          files.remove(file.toNIO)
        }
        // If Java file, treat deletion as a change to an empty file.
        // This adds an empty source to SOURCE_PATH so javac won't find the class.
        // We also track deleted binary names to exclude from CLASS_PATH.
        if (doc.language.isJava && javaSymbolLoader().isTurbineClasspath) {
          val binaryNames = doc.symbols
            .map(_.getSymbol())
            .filter(sym => Symbol(sym).isToplevel)
            .map(sym => sym.stripSuffix("#").stripSuffix("."))
            .toSeq
          // Track deleted binary names for CLASS_PATH exclusion
          turbineCompiler.onDidDelete(binaryNames, file.toURI.toString())
          // Add empty file to SOURCE_PATH so javac parses it and doesn't find the class
          doc.semanticdbPackages.headOption match {
            case Some(pkg) =>
              val packageName = pkg.stripSuffix("/").replace("/", ".")
              val emptyCompilationUnit = VirtualTextDocument(
                SourceJavaFileObject.makeRelativeURI(file.toURI),
                pc.Language.JAVA,
                "", // Empty content - class is no longer defined
                doc.semanticdbPackages,
                Nil, // No toplevel symbols
              )
              turbineCompiler
                .onDidChange(packageName, emptyCompilationUnit)
                .map(_ => ())
            case None =>
              Future.unit
          }
        } else {
          Future.unit
        }
    }
  }
  def onDidChange(file: AbsolutePath): Future[Unit] = {
    onDidChangeInternal(file, updateDocumentKeys = true)
  }

  private def onDidChangeInternal(
      file: AbsolutePath,
      updateDocumentKeys: Boolean,
  ): Future[Unit] = try {
    val mdoc =
      IndexedDocument.fromFile(file, mtags(), buffers, dialects.Scala213)
    putDocument(file, mdoc, updateDocumentKeys = updateDocumentKeys)
  } catch {
    case _: UnexpectedInputEndException =>
      scribe.debug(s"${file}: syntax error")
      Future.unit
    case NonFatal(e) =>
      scribe.error(s"Error indexing file $file", e)
      Future.unit
  }

  private def toInput(
      file: AbsolutePath
  ): Option[Input.VirtualFile] = try {
    Some(file.toInputFromBuffers(buffers))
  } catch {
    case _: java.nio.file.NoSuchFileException =>
      onDidDelete(file)
      None
  }

  override def createFileManager(
      standardFileManager: StandardJavaFileManager
  ): JavaFileManager = {
    if (javaSymbolLoader().isJavacSourcepath) {
      new JavacSourcepathFileManager(
        standardFileManager,
        (pkg) => {
          documentsByPackage.get(pkg) match {
            case None =>
              ju.Collections.emptyList()
            case Some(paths) =>
              val result = for {
                path <- paths.asScala.iterator
                doc <- documents.get(AbsolutePath(path)).toList.iterator
                if doc.language.isJava
                input <- toInput(doc.file)
              } yield doc.toSemanticdbCompilationUnit(input)
              ArrayBuffer.from(result).asJava
          }
        },
      )
    } else if (javaSymbolLoader().isTurbineClasspath) {
      turbineCompiler.createFileManager(standardFileManager)
    } else {
      throw new IllegalArgumentException(
        s"unexpected javaSymbolLoader config: ${javaSymbolLoader()}"
      )
    }
  }

  override def listAllPackages(): ju.Map[String, ju.Set[Path]] = {
    documentsByPackage
      .mapValues(set => ju.Collections.unmodifiableSet(set))
      .toMap
      .asJava
  }

  def document(file: AbsolutePath): Option[IndexedDocument] = {
    documents.get(file)
  }

  def definition(symbol: String): List[l.Location] = {
    val result = (for {
      file <- documentsByPackage
        .getOrElse(
          Symbol(symbol).enclosingPackage.value,
          new ju.concurrent.ConcurrentSkipListSet[Path](),
        )
        .asScala
        .iterator
      doc <- documents.get(AbsolutePath(file)).iterator
      sym <- doc.symbols.iterator
      if sym.getSymbol() == symbol
    } yield {
      new l.Location(
        file.toUri().toString(),
        new l.Range(
          new l.Position(
            sym.getDefinitionRange().getStartLine(),
            sym.getDefinitionRange().getStartCharacter(),
          ),
          new l.Position(
            sym.getDefinitionRange().getEndLine(),
            sym.getDefinitionRange().getEndCharacter(),
          ),
        ),
      )
    }).toList
    result
  }

  def possibleReferences(
      params: MbtPossibleReferencesParams
  ): Iterable[AbsolutePath] = {
    val queries = HashSet.empty[String]
    params.implementations.foreach { symbol =>
      val sym = Symbol(symbol)
      if (sym.isMethod) {
        queries += s"${sym.displayName}():"
      } else if (sym.isType) {
        queries += s"${sym.displayName}:"
      } else {
        scribe.warn(
          s"mbt-v2: unexpected implementation symbol for possibleReferences: ${symbol}"
        )
      }
    }
    params.references.foreach { ref =>
      val sym = Symbol(ref)
      if (sym.isGlobal) {
        if (sym.isConstructor) {
          queries += s"${sym.owner.displayName}."
        } else if (sym.isMethod) {
          queries += s"${sym.displayName}()."
        } else {
          queries += s"${sym.displayName}."
          queries += s"${sym.displayName}:"
          // Also search for method references - Java accesses Scala vals as methods
          queries += s"${sym.displayName}()."
        }
      }
    }
    val fingerprints =
      queries.iterator.map(FingerprintedCharSequence.fuzzyReference).toBuffer
    val result = new ju.concurrent.ConcurrentLinkedDeque[AbsolutePath]()
    for {
      path <- documentsKeys.toList
      doc <- documents.get(path).toList.iterator
      if fingerprints.exists(query => doc.bloomFilter.mightContain(query))
    } {
      if (path.exists) {
        result.add(path)
      } else {
        // Clean up removed files
        documents.remove(path)
      }
    }
    result.asScala
  }

  // Convenience method to avoid dealing with the visitor-based query API
  // (including concurrency).  Mostly useful for testing. In production, use the
  // visitor-based API.
  final def queryWorkspaceSymbol(
      query: String
  ): List[l.SymbolInformation] = {
    val visitor = new SimpleCollectingSymbolSearchVisitor()
    workspaceSymbolSearch(
      new MbtWorkspaceSymbolSearchParams(query, ""),
      visitor,
    )
    visitor.results.asScala.toList
  }

  def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    if (!config().isMBT) {
      scribe.warn(
        s"mbt-v2: config is not mbt-v2, skipping workspace symbol search"
      )
      return SymbolSearch.Result.COMPLETE
    }

    if (params.buildTargetIdentifier.nonEmpty) {
      throw new UnsupportedOperationException(
        s"mbt-v2: build target identifier is not supported yet. Got: '${params.buildTargetIdentifier}'"
      )
    }

    val maxResults = 300
    val fuzzyQuery = WorkspaceSymbolQuery.fuzzy(params.query)
    val exactQuery = WorkspaceSymbolQuery.exactDescriptorPart(params.query)
    val resultCount = new AtomicInteger(0)
    val remainingFilesCount = new AtomicInteger(documentsKeys.length)

    // Step 1: filter out what files are *likely* to contain a match, per bloom
    // filter tests.
    val exactMatches =
      new ju.concurrent.ConcurrentLinkedQueue[IndexedDocument]()
    val fuzzyMatches =
      new ju.concurrent.ConcurrentLinkedQueue[IndexedDocument]()
    for {
      path <- documentsKeys
      if !visitor.isCancelled() && resultCount.get() < maxResults
      _ = remainingFilesCount.decrementAndGet()
      doc <- documents.get(path).toList.iterator
      if fuzzyQuery.matches(doc.bloomFilter)
    } {
      if (exactQuery.matches(doc.bloomFilter)) {
        exactMatches.add(doc)
      } else {
        fuzzyMatches.add(doc)
      }
    }

    // Step 2: brute-force fuzzy search through all the symbols in the documents
    // with potential matches.
    val candidates = ParArray.fromSpecific(
      Iterator(
        exactMatches.asScala.iterator,
        fuzzyMatches.asScala.iterator,
      ).flatten
    )
    for {
      doc <- candidates
      if !visitor.isCancelled() && resultCount.get() < maxResults
      info <- doc.symbols
      if fuzzyQuery.matches(info.getSymbol())
    } {
      resultCount.addAndGet(
        visitor.visitWorkspaceSymbol(
          doc.file.toNIO,
          info.getSymbol,
          info.getKind.toLsp,
          info.getDefinitionRange().toLspRange,
        )
      )
    }

    if (remainingFilesCount.get() > 0) {
      SymbolSearch.Result.INCOMPLETE
    } else {
      SymbolSearch.Result.COMPLETE
    }
  }

  private def putDocument(
      file: AbsolutePath,
      doc: IndexedDocument,
      updateDocumentKeys: Boolean,
  ): Future[Unit] = {
    val old = documents.put(file, doc)
    if (old == None && updateDocumentKeys) {
      updateDocumentsKeys(documents)
    }
    addDocumentToPackages(doc.semanticdbPackages, file)
    if (
      updateDocumentKeys &&
      doc.language.isJava &&
      javaSymbolLoader().isTurbineClasspath
    ) {
      doc.semanticdbPackages.headOption match {
        case Some(pkg) =>
          val input = file.toInputFromBuffers(buffers)
          val packageName = pkg.stripSuffix("/").replace("/", ".")
          val compilationUnit = doc.toSemanticdbCompilationUnit(input)
          turbineCompiler.onDidChange(packageName, compilationUnit).ignoreValue
        case None =>
          Future.unit
      }
    } else {
      Future.unit
    }
  }

  private def addDocumentToPackages(
      pkgs: Seq[String],
      file: AbsolutePath,
  ): Unit = {
    for (pkg <- pkgs) {
      val files = documentsByPackage.getOrElseUpdate(
        pkg,
        new ju.concurrent.ConcurrentSkipListSet[Path](
          new ju.Comparator[Path]() {
            override def compare(o1: Path, o2: Path): Int = {
              o1.toString.compareTo(o2.toString)
            }
          }
        ),
      )
      files.add(file.toNIO)
    }
  }

  // Dumps the current in-memory index to .metals/index.mbt. This overwrites the
  // old index, which effectively works like basic garbage collection. We don't
  // need 100% cache hits so it's fine to re-index all the changed files every
  // time you checkout between two git commits. We just want to avoid 1) re-indexing
  // 100k files on startup and 2) having an index that grows unbounded.
  private def writeIndex(): Unit = try {
    indexFile.parent.createDirectories()
    val tmp = workspace
      // We create the tmp file under .metals/ because atomic moves can fail on
      // Linux if /tmp is on a different mount than the workspace.
      .resolve(Directories.outDir)
      // Use a random number there are multiple Metals servers indexing the same
      // workspace at the same time, which should be rare, but can happen.
      .resolve(s"index.mbt.${new Random().nextInt()}.tmp")
    tmp.deleteIfExists()
    tmp.parent.createDirectories()
    val bufferedOutputStream = new BufferedOutputStream(
      Files.newOutputStream(
        tmp.toNIO,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
      )
    )
    tmp.toFile.deleteOnExit()
    Using(bufferedOutputStream) { out =>
      documents.foreach { case (path, doc) =>
        if (!path.exists) {
          this.documents.remove(path)
        } else {
          // Append one document at a time to the output stream to avoid holding
          // a full copy of the binary payload in memory.  This is the main
          // reason why index.mbt uses protobuf instead of JSON, it's not
          // because Protobuf is super fast or super compact.
          doc.toIndexProto().writeTo(out)
        }
      }
    }
    try {
      Files.move(tmp.toNIO, indexFile.toNIO, StandardCopyOption.ATOMIC_MOVE)
    } catch {
      case NonFatal(e) =>
        scribe.warn(
          s"mbt-v2: failed to move '${tmp}' to '${indexFile}' atomically, trying non-atomic move.",
          e,
        )
        // Fallback to non-atomic move.
        Files.move(tmp.toNIO, indexFile.toNIO)
    }
  } catch {
    case NonFatal(e) =>
      scribe.error(s"mbt-v2:Error writing index file ${indexFile}", e)
  }

  private def updateDocumentsKeys(
      documentsIndex: TrieMap[AbsolutePath, IndexedDocument]
  ): ParArray[AbsolutePath] = {
    val newValue = ParArray.fromSpecific(documentsIndex.keysIterator)
    documentsKeys = newValue
    // update the document keys is the last step when indexing, as it prepares
    // the parallel array for the next workspace symbol search, it's the right moment
    // to notify others that the indexing is done.
    onIndexingDone()
    newValue
  }

  // Reads .metals/index.mbt, which is a serialized Mbt.Index protobuf payload,
  // into memory and converts it into TrieMap[AbsolutePath, IndexedDocument].
  // For a very large repo (>100k Scala/Java files), this file still only takes
  // ~500mb of ram.
  private def readIndex(): TrieMap[AbsolutePath, IndexedDocument] = try {
    val result = TrieMap.empty[AbsolutePath, IndexedDocument]
    if (indexFile.exists) {
      val timer = new Timer(time)
      val index = Mbt.Index.parseFrom(indexFile.readAllBytes)
      for {
        doc <- index.getDocumentsList().asScala.iterator
        // Don't load old and incompatible versions of indexed files
        if IndexedDocument.matchesCurrentVersion(doc)
      } {
        try {
          val path = AbsolutePath.fromAbsoluteUri(URI.create(doc.getUri()))
          addDocumentToPackages(
            doc.getSemanticdbPackageList().asScala.toList,
            path,
          )
          result.put(path, IndexedDocument.fromProto(path, doc))
        } catch {
          case NonFatal(e) =>
            scribe.error(s"Error reading index file ${doc.getUri()}", e)
        }
      }
      scribe.info(
        s"mbt-v2: read index for ${result.size} files in ${timer}"
      )
    }
    updateDocumentsKeys(result)
    result
  } catch {
    case NonFatal(e) =>
      scribe.error(s"Error reading repo-wide symbol index at '${indexFile}'", e)
      TrieMap.empty[AbsolutePath, IndexedDocument]
  }

}
