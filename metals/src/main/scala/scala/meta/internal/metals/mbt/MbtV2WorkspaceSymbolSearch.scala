package scala.meta.internal.metals.mbt

import java.io.BufferedOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.dialects
import scala.meta.infra.Event
import scala.meta.infra.MonitoringClient
import scala.meta.internal.infra.NoopMonitoringClient
import scala.meta.internal.jmbt.Mbt
import scala.meta.internal.metals.BaseWorkDoneProgress
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.tokenizers.UnexpectedInputEndException
import scala.meta.io.AbsolutePath
import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

class MbtV2WorkspaceSymbolSearch(
    val workspace: AbsolutePath,
    config: () => WorkspaceSymbolProviderConfig = () =>
      WorkspaceSymbolProviderConfig.default,
    buffers: Buffers = Buffers(),
    time: Time = Time.system,
    metrics: MonitoringClient = new NoopMonitoringClient(),
    mtags: () => Mtags = () => Mtags.testingSingleton,
    progress: BaseWorkDoneProgress = EmptyWorkDoneProgress,
)(implicit val ec: ExecutionContext)
    extends MbtWorkspaceSymbolSearch {

  private val indexFile: AbsolutePath = workspace.resolve(".metals/index.mbt")

  override def close(): Unit = {}

  // The source of truth for what files belong to the workspace, and their attached indexed data.
  // DO NOT update this map directly since have a couple derivative collections.
  // Instead, use the following methods to update the index:
  // - onDidChange(file: AbsolutePath): Unit
  // - onDidDelete(file: AbsolutePath): Unit
  // - onDidChangeSymbols(params: OnDidChangeSymbolsParams): Unit
  private lazy val documents: TrieMap[AbsolutePath, IndexedDocument] =
    readIndex()
  // `documentsKeys` is effectively `documents.keys.par` but without the
  // overhead to copy the keys into a parallel collection at query time.  Make
  // sure to call updateDocumentsKeys() when you add or remove a document.
  @volatile private var documentsKeys = ParArray.empty[AbsolutePath]

  // Maps SemanticDB package symbol (for example, "scala/collection/") to all
  // the files that directly belong to that package. This index powers repo-wide
  // -sourcepath imports for JavaPruneCompilerFileManager. It's important to manually
  private val documentsByPackage =
    TrieMap.empty[String, ju.concurrent.ConcurrentSkipListSet[Path]]

  override def onReindex(): IndexingStats = {
    if (!config().isMBT2) {
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
    task.maybeProgress.foreach(_.update(0, toIndex.length))

    val indexedFilesCount = new AtomicInteger()
    // Step 3: The actual indexing, happens in parallel. Treat these as regular
    // didChange events for each individual file.
    toIndex.foreach { file =>
      onDidChangeInternal(file, updateDocumentKeys = false)
      val count = indexedFilesCount.incrementAndGet()
      if (count % 50 == 0) {
        task.maybeProgress.foreach(_.update(count, toIndex.length))
      }
    }
    updateDocumentsKeys(documents)

    val end = new l.WorkDoneProgressEnd()
    end.setMessage(s"done in $timer")
    progress.endProgress(token)

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
      backgroundJobs = Future {
        // We intentionally exclude `git status` from the metrics because this command
        // can take a very long time to run in large repos and it's not something we
        // can optimize.
        synchronizeWithGitStatus()
      },
    )
  }

  private def synchronizeWithGitStatus(): Unit = {
    GitVCS
      .status(workspace)
      .foreach(file => this.onDidChange(file.file))
  }

  def onDidChangeSymbols(
      params: OnDidChangeSymbolsParams
  ): Unit = {
    val indexedDoc = IndexedDocument.fromOnDidChangeParams(params)
    putDocument(params.path, indexedDoc, updateDocumentKeys = true)
  }
  def onDidDelete(file: AbsolutePath): Unit = {
    for {
      doc <- documents.remove(file)
      _ = updateDocumentsKeys(documents)
      files <- documentsByPackage.get(doc.semanticdbPackage)
    } {
      files.remove(file.toNIO)
    }
  }
  def onDidChange(file: AbsolutePath): Unit = {
    onDidChangeInternal(file, updateDocumentKeys = true)
  }

  private def onDidChangeInternal(
      file: AbsolutePath,
      updateDocumentKeys: Boolean,
  ): Unit = try {
    val mdoc =
      IndexedDocument.fromFile(file, mtags(), buffers, dialects.Scala213)
    putDocument(file, mdoc, updateDocumentKeys = updateDocumentKeys)
  } catch {
    case _: UnexpectedInputEndException =>
      scribe.debug(s"${file}: syntax error")
    case NonFatal(e) =>
      scribe.error(s"Error indexing file $file", e)
  }

  override def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] = {
    documentsByPackage.get(pkg) match {
      case None =>
        ju.Collections.emptyList()
      case Some(paths) =>
        val result = for {
          path <- paths.asScala.iterator
          doc <- documents.get(AbsolutePath(path)).toList.iterator
          if doc.language.isJava
        } yield doc.toSemanticdbCompilationUnit(buffers)
        ArrayBuffer.from(result).asJava
    }
  }

  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    if (!config().isMBT2) {
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
  ): Unit = {
    val old = documents.put(file, doc)
    if (old == None && updateDocumentKeys) {
      updateDocumentsKeys(documents)
    }
    addDocumentToPackage(doc.semanticdbPackage, file)
  }

  private def addDocumentToPackage(pkg: String, file: AbsolutePath): Unit = {
    val files = documentsByPackage.getOrElseUpdate(
      pkg,
      new ju.concurrent.ConcurrentSkipListSet[Path](),
    )
    files.add(file.toNIO)
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
          addDocumentToPackage(doc.getSemanticdbPackage(), path)
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
