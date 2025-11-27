package scala.meta.internal.metals.mbt
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.{util => ju}

import scala.collection.mutable
import scala.collection.parallel.CollectionConverters._
import scala.util.Try
import scala.util.Using

import scala.meta.dialects
import scala.meta.infra
import scala.meta.inputs.Input
import scala.meta.internal.infra.NoopMonitoringClient
import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CancelTokens
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.metals.Memory
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatisticsConfig
import scala.meta.internal.metals.StringBloomFilter
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.TimerProvider
import scala.meta.internal.metals.WorkspaceSymbolQuery
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.tokenizers.UnexpectedInputEndException
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.pc.SemanticdbCompilationUnit
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}
import org.lmdbjava.Env

/**
 * MbtWorkspaceSymbolProvider is an LSP workspace/symbol implementation that
 * indexes all the Scala/Java files in the provided git repo using the mtags
 * "toplevel" indexers.
 *
 * Importantly, this indexer does *not* depend on BSP, it indexes all
 * *.{scala,java} files that are checked into the git repo. This index does
 * nothing if the workspace is not a git repo, but we could totally add support
 * to index git-free folders if someone wants to take a stab at implementing it.
 *
 * Simplified, this class implements the strategy documented
 * [here](https://scalameta.org/metals/blog/2019/01/22/bloom-filters/#fuzzy-symbol-search)
 * but it stores the TextDocument payloads in an LMDB database instead of
 * keeping them in-memory like we do with WorkspaceSymbolsIndex.
 *
 * This implementation is designed to perform fast even in large repos. For
 * example, it takes ~2.5s to index all of akka/akka on a cold cache with a
 * well-specced Linux computer. It takes ~0.3s to load the the akka/akka index
 * on a hot cache, and incremental updates are processed at ~300-600k loc/s
 * depending on a variety of factors like coding style, split between
 * Java/Scala, etc.
 *
 * NOTE: this class will get replaced with MbtV2WorkspaceSymbolSearch since it
 * was too complicated to retrofit incremental index updates to this array-based
 * LMDB index.
 */
final class MbtWorkspaceSymbolProvider(
    val gitWorkspace: AbsolutePath,
    config: () => WorkspaceSymbolProviderConfig = () =>
      WorkspaceSymbolProviderConfig.default,
    statistics: () => StatisticsConfig = () => StatisticsConfig.default,
    mtags: () => Mtags = () => Mtags.testingSingleton,
    metrics: infra.MonitoringClient = new NoopMonitoringClient(),
    buffers: Buffers = Buffers(),
    timerProvider: TimerProvider = TimerProvider.empty,
) extends MbtWorkspaceSymbolSearch
    with Cancelable
    with SemanticdbFileManager {

  override def close(): Unit = {
    db.close()
  }

  def listPackage(pkg: String): ju.List[SemanticdbCompilationUnit] = {
    timerProvider.timedThunk(
      s"SemanticdbFileManager.listPackage $pkg (index size ${index.size})",
      thresholdMillis = 200,
    ) {
      db.readTransaction[ju.List[SemanticdbCompilationUnit]](
        tableName,
        "listPackage",
        ju.Collections.emptyList(),
      ) { (env, db, txn, _) =>
        val keyBuffer = ByteBuffer.allocateDirect(env.getMaxKeySize())
        val result = new ConcurrentLinkedQueue[SemanticdbCompilationUnit]()
        index.foreach { f =>
          if (
            f.language == Semanticdb.Language.JAVA &&
            f.semanticdbPackage == pkg
          ) {
            val doc = this.readTextDocument(keyBuffer, db, txn, f)
            if (doc.uri.nonEmpty && f.path.exists) {
              val text = AbsolutePath(f.path).toInputFromBuffers(buffers).text
              result.add(
                VirtualTextDocument.fromDocument(
                  f.language.toPCLanguage,
                  List(f.semanticdbPackage),
                  f.toplevelSymbols,
                  doc.copy(text = text),
                )
              )
            }
          }
        }
        new ju.ArrayList(result)
      }
    }
  }

  override def listAllPackages(): ju.Map[String, ju.Set[Path]] = {
    ju.Collections.emptyMap()
  }

  private val isStatisticsEnabled: Boolean = statistics().isWorkspaceSymbol

  private val db = new LMDB(gitWorkspace)
  // The workspace symbol index is, simplified, a list of bloom filters, where
  // each bloom filter represents the set of symbols that are defined in a
  // single file. This could technically be a Map[Path, StringBloomFilter] but
  // it's an array since it's cleaner to process it in parallel like that.
  @volatile
  private var index = mutable.ArrayBuffer.empty[OIDIndex]

  // The maximum number of symbol results to return. Users only look at the top
  // 20 results, nobody scrolls down the list, so we could probably cut this
  // down even further.  We need an upper limit to keep latency down. For
  // example, the query "Suite" could match all test suites in the repo
  // returning thousands of results.
  private val maxResults = 300
  // The LMDB table name.
  private val tableName = "workspaceSymbols"
  // Bump up this version if we make a change to how indexing happens. For
  // example, if we start indexing Java methods, then we can bump up this
  // version to invalidate the cache for old payloads. TODO: implement the logic
  // to remove old payloads if you bump this version.
  private val dbVersion = 1
  // The "workspaceSymbols" includes SemanticDB TextDocument payloads under this
  // subkey.  We could have technically used different tables, but then we
  // couldn't reuse the same `openDbi` call.
  private val tableSemanticdbSubkey = 1
  // The "workspaceSymbols" includes serialized bloom filters under this subkey.
  private val tableBloomFilterSubkey = 2

  override def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result = {
    if (!config().isMBT1) {
      scribe.error(
        "invalid state, MbtWorkspaceSymbolProvider.search cannot be used when config.isMBT1 is false"
      )
      return SymbolSearch.Result.INCOMPLETE
    }
    this.onQueryWorkspaceSymbol(
      new l.WorkspaceSymbolParams(params.query),
      CancelTokens.empty,
      visitor = (
          doc: s.TextDocument,
          info: s.SymbolInformation,
          occ: s.SymbolOccurrence,
      ) => {
        if (!doc.uri.startsWith("file://")) {
          throw new IllegalArgumentException(s"invalid uri: ${doc.uri}")
        }
        val path = Paths.get(URI.create(doc.uri))
        visitor.visitWorkspaceSymbol(
          path,
          info.symbol,
          info.kind.toLsp,
          occ.range.getOrElse(s.Range()).toLsp,
        )
        if (visitor.isCancelled()) Stop else Continue
      },
    )
    SymbolSearch.Result.COMPLETE
  }

  def queryWorkspaceSymbol(
      query: String,
      token: CancelToken = CancelTokens.empty,
  ): List[l.SymbolInformation] = {
    queryWorkspaceSymbol(new l.WorkspaceSymbolParams(query), token)
  }

  def queryWorkspaceSymbol(
      params: l.WorkspaceSymbolParams,
      token: CancelToken,
  ): List[l.SymbolInformation] = {
    val result = new ConcurrentLinkedQueue[l.SymbolInformation]()
    onQueryWorkspaceSymbol(
      params,
      token,
      visitor = (
          doc: s.TextDocument,
          info: s.SymbolInformation,
          symbol: s.SymbolOccurrence,
      ) => {
        val (desc, owner) = DescriptorParser(info.symbol)
        result.add(
          new l.SymbolInformation(
            desc.name.value,
            info.kind.toLsp,
            new l.Location(
              doc.uri,
              symbol.range.fold(new l.Range())(r => r.toLsp),
            ),
            owner.replace('/', '.'),
          )
        )
        if (result.size >= maxResults) Stop
        else Continue
      },
    )
    result.asScala.toList
  }
  private def onQueryWorkspaceSymbol(
      params: l.WorkspaceSymbolParams,
      token: CancelToken,
      visitor: MbtSymbolSearchVisitor,
  ): Unit = {
    if (!config().isMBT1) {
      scribe.error(
        "invalid state, MbtWorkspaceSymbolProvider.workspaceSymbol cannot be used when config.isMBT1 is false"
      )
      return
    }
    db.readTransaction[Unit](
      tableName,
      s"query(${params.getQuery})",
      (),
    ) { (env, db, txn, _) =>
      workspaceSymbolUnsafe(params, env, db, txn, token, visitor)
    }
  }

  private case class Match(file: OIDIndex, isExact: Boolean)
  private def workspaceSymbolUnsafe(
      params: l.WorkspaceSymbolParams,
      env: Env[ByteBuffer],
      db: org.lmdbjava.Dbi[ByteBuffer],
      txn: org.lmdbjava.Txn[ByteBuffer],
      token: CancelToken,
      visitor: MbtSymbolSearchVisitor,
  ): Unit = {
    val timer = new Timer(Time.system)
    if (index.isEmpty) {
      scribe.error(s"workspace/symbol index is empty")
      return
    }
    val query = WorkspaceSymbolQuery.fuzzy(params.getQuery)
    val exactQuery =
      WorkspaceSymbolQuery.exactDescriptorPart(params.getQuery)
    val keyBuffer = ByteBuffer.allocateDirect(env.getMaxKeySize())
    if (token.isCanceled()) {
      return
    }

    val oids = new ConcurrentLinkedQueue[Match]()
    index.par
      .foreach { f =>
        val isFuzzyMatch = query.matches(f.documentSymbols)
        if (isFuzzyMatch) {
          oids.add(Match(f, exactQuery.matches(f.documentSymbols)))
        }
      }

    val rankedOIDs = mutable.ArrayBuffer.empty[Match].addAll(oids.asScala)
    // Boost results where the symbol name is an exact match. Without this, the
    // query "InputStream" may return results like `createInputStream` at the
    // top. NOTE: we don't have a guarantee yet they're at the top because we
    // need to do this sort one more time at the end but it's only relevant if
    // we get >300 exact matches, at which point it won't help much anyways.
    rankedOIDs.sortInPlaceBy(m => if (m.isExact) 0 else 1)
    val cursor = db.openCursor(txn)
    val semanticdbs = mutable.ArrayBuffer.empty[TextDocument]
    semanticdbs.appendAll(
      rankedOIDs.iterator.map(o => readTextDocument(keyBuffer, db, txn, o.file))
    )
    if (token.isCanceled()) {
      return
    }
    cursor.close()
    txn.close()
    val result =
      new ju.concurrent.atomic.AtomicReference[MbtSymbolSearchResult](Continue)
    val resultCount = new ju.concurrent.atomic.AtomicInteger(0)
    for {
      doc <- semanticdbs.par
      if result.get() == Continue
      symbol <- doc.occurrences
      if query.matches(symbol.symbol)
    } {
      // TODO: optimize this naive lookup
      val info = doc.symbols
        .find(_.symbol == symbol.symbol)
        .getOrElse(s.SymbolInformation())
      result.compareAndSet(Continue, visitor.onMatch(doc, info, symbol))
      resultCount.incrementAndGet()
    }
    if (isStatisticsEnabled) {
      scribe.info(
        s"workspace/symbol query '${params.getQuery}' matched ${resultCount.get()} results in ${timer}, sourced from ${rankedOIDs.length} candidate files"
      )
    }
  }

  // NOTE(olafurpg):`onDidChange` is not supported with mbt-v1. I gave up trying
  // because it was too complicated to implement it the array-based index and
  // LMDB backend.  In mbt-v2, this is much much simpler to implement because
  // it's using the same TrieMap[AbsolutePath, IndexedDocument] shape as all the
  // other indexers in Metals.
  def onDidChange(file: AbsolutePath): Unit = ()
  def onDidDelete(file: AbsolutePath): Unit = ()
  def onDidChangeSymbols(params: OnDidChangeSymbolsParams): Unit = ()

  override def onReindex(): IndexingStats = {
    if (!config().isMBT1) {
      return IndexingStats.empty
    }
    // Stage 0: discover the files that need to be indexed
    scribe.debug("workspace/symbol indexing started")
    val timer = new Timer(Time.system)
    val files =
      // TODO: avoid copy here
      mutable.ArrayBuffer.from(GitVCS.lsFilesStage(gitWorkspace).iterator)
    if (files.isEmpty) {
      // An error is logged if GitVCS.lsFilesStage fails.
      scribe.warn("workspace/symbol indexing found no files to index")
      return IndexingStats.empty
    }
    if (isStatisticsEnabled) {
      scribe.info(
        f"time: workspace/symbol discovered ${files.length}%,d files in ${timer}"
      )
    }

    val missingKeys =
      db.writeTransaction(
        tableName,
        "missingKeys",
        mutable.ArrayBuffer.empty[Int],
      ) { (env, db, txn, use) =>
        // Step 1: open the database
        val keyBuffer = ByteBuffer.allocateDirect(env.getMaxKeySize())
        val valueBuffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
        val cursor = db.openCursor(txn)

        // Step 2: discover the files that need to be indexed
        val missingKeys =
          readCacheAndReturnMissingKeys(keyBuffer, files, db, txn)
        val cachedFilesCount = files.length - missingKeys.length

        // Step 3: read the text contents of the missing files into memory
        readGitFiles(files, missingKeys, use)
        if (isStatisticsEnabled) {
          scribe.info(
            s"time: workspace/symbol read ${missingKeys.length} cold files and ${cachedFilesCount} hot files in ${timer}"
          )
        }

        // Step 4: index the missing files
        indexMissingFiles(mtags(), files, missingKeys)

        // Step 5: write the indexed files to the database
        missingKeys.foreach { index =>
          val file = files(index)
          keyBuffer
            .clear()
            .putInt(dbVersion)
            .putInt(tableSemanticdbSubkey)
            .put(file.oidBytes)
            .flip()
          valueBuffer.clear().put(file.semanticdb.toByteArray).flip()
          cursor.put(keyBuffer, valueBuffer)

          keyBuffer
            .clear()
            .putInt(dbVersion)
            .putInt(tableBloomFilterSubkey)
            .put(file.oidBytes)
            .flip()
          valueBuffer.clear().put(file.bloomFilter.toBytes).flip()
          cursor.put(keyBuffer, valueBuffer)
          missingKeys
        }
        txn.commit()
        missingKeys
      }

    // Step 6: assemble the in-memory index. Only the bloom filters, file
    // paths (for debugging) and OIDs are needed.
    val newIndex =
      mutable.ArrayBuffer.from(files.iterator.map(_.toOIDIndex(gitWorkspace)))
    newIndex.sortInPlace()(Ordering.by[OIDIndex, Path](_.path))
    index = newIndex
    files.clear()

    metrics.recordEvent(
      infra.Event.duration("mbt_index_workspace_symbol", timer.elapsed)
    )

    // Step 7: print logs
    if (statistics().isMemory) {
      Memory.logMemory(List(("workspace/symbol index", index)))
    }
    val elapsed = timer.elapsedNanos.toDouble / 1_000_000_000

    if (isStatisticsEnabled && missingKeys.length > 0) {
      val linesPerSecond =
        if (elapsed > 0) mtags().totalLinesOfCode / elapsed else 0
      scribe.info(
        f"time: workspace/symbol indexed ${missingKeys.length} files (${linesPerSecond.toInt}%,d loc/s) in ${timer}"
      )
    }
    IndexingStats(
      totalFiles = index.length,
      updatedFiles = missingKeys.length,
    )
  }

  private def readCacheAndReturnMissingKeys(
      keyBuffer: ByteBuffer,
      files: mutable.ArrayBuffer[GitBlob],
      db: org.lmdbjava.Dbi[ByteBuffer],
      txn: org.lmdbjava.Txn[ByteBuffer],
  ): mutable.ArrayBuffer[Int] = {
    val missingKeys = mutable.ArrayBuffer.empty[Int]
    files.iterator.zipWithIndex.foreach { case (file, index) =>
      keyBuffer
        .clear()
        .putInt(dbVersion)
        .putInt(tableBloomFilterSubkey)
        .put(file.oidBytes)
        .flip()
      val valueFromDb = db.get(txn, keyBuffer)
      if (valueFromDb != null) {
        val bloomFilterBytes = new Array[Byte](valueFromDb.remaining())
        valueFromDb.get(bloomFilterBytes)
        file.bloomFilter = StringBloomFilter.fromBytes(bloomFilterBytes)

        // TODO: remove me, we should not hold onto the SemanticDBs in memory here
        // We're only doing it temporarily to compute the toplevel package name and toplevel
        keyBuffer
          .clear()
          .putInt(dbVersion)
          .putInt(tableSemanticdbSubkey)
          .put(file.oidBytes)
          .flip()
        val semanticdbFromDb = db.get(txn, keyBuffer)
        if (semanticdbFromDb != null) {
          val semanticdbBytes = new Array[Byte](semanticdbFromDb.remaining())
          semanticdbFromDb.get(semanticdbBytes)
          file.semanticdb = TextDocument.parseFrom(semanticdbBytes)
        }
      } else {
        missingKeys.append(index)
      }

    }
    missingKeys
  }

  private def indexMissingFiles(
      mtags: Mtags,
      files: mutable.ArrayBuffer[GitBlob],
      missingKeys: mutable.ArrayBuffer[Int],
  ): Unit = {
    if (missingKeys.isEmpty) {
      return
    }
    missingKeys.par.foreach { index =>
      val file = files(index)
      val result = Try {
        val input =
          Input.VirtualFile(
            gitWorkspace.resolve(file.path).toURI.toString(),
            new String(file.textBytes, StandardCharsets.UTF_8),
          )
        // No need to hold on the text contents after we have read them
        file.textBytes = null
        mtags.indexMBT(
          file.toJLanguage,
          input,
          dialects.Scala213,
          includeReferences = false,
        )
      }.recover {
        case _: UnexpectedInputEndException =>
          scribe.error(s"${file.path}: syntax error")
          TextDocument()
        case e =>
          scribe.error(
            s"Error while indexing workspace symbol for file '${file.path}'",
            e,
          )
          TextDocument()
      }
      file.semanticdb = result.get
      file.bloomFilter =
        Fuzzy.bloomFilterSymbolStrings(result.get.occurrences.map(_.symbol))
    }
  }

  private def readGitFiles(
      files: mutable.ArrayBuffer[GitBlob],
      missingKeys: mutable.ArrayBuffer[Int],
      use: Using.Manager,
  ): Unit = {
    if (missingKeys.isEmpty) {
      return
    }
    val oids =
      missingKeys.map(i => files(i).oid).mkString("\n")
    // NOTE: we read *all* Scala/Java files in the repo into memory here. This
    // is not an inherent requirement, it's just convenient. It's only ~1gb of
    // ram, and we only hold it temporarily. The final index only needs ~256mb
    // of ram. We should make sure Metals has enough -Xmx to do this instead of
    // trying to make the indexing pipeline work with <<1gb of ram.
    val reader = use(GitCat.batch(stdin = oids, cwd = gitWorkspace)).toArray
    if (reader.length != missingKeys.length) {
      throw new RuntimeException(
        s"Oid mismatch: ${reader.length} != ${missingKeys.length}"
      )
    }
    reader.iterator.zip(missingKeys.iterator).foreach {
      case (GitBlobBytes(oid, bytes), index) =>
        val file = files(index)
        if (file.oid != oid) {
          throw new RuntimeException(
            s"Oid mismatch: ${file.oid} != ${oid} (for file path ${file.path})"
          )
        }
        file.textBytes = bytes
    }
  }

  private def readTextDocument(
      keyBuffer: ByteBuffer,
      db: org.lmdbjava.Dbi[ByteBuffer],
      txn: org.lmdbjava.Txn[ByteBuffer],
      file: OIDIndex,
  ): TextDocument = {
    keyBuffer
      .clear()
      .putInt(dbVersion)
      .putInt(tableSemanticdbSubkey)
      .put(file.oid)
      .flip()
    val value = db.get(txn, keyBuffer)
    if (value != null) {
      val bytes = new Array[Byte](value.remaining())
      value.get(bytes)
      TextDocument.parseFrom(bytes)
    } else {
      scribe.error(s"TextDocument not found for file ${file.path}")
      TextDocument()
    }
  }

  override def cancel(): Unit = {
    db.close()
  }
}
