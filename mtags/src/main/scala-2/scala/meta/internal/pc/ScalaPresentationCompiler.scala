package scala.meta.internal.pc

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.{util => ju}

import scala.collection.Seq
import scala.collection.mutable
import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.ParsedLogicalPackage
import scala.tools.nsc.Settings
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.SimpleTimer
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.{semanticdb => s}
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CodeActionId
import scala.meta.pc.CompletionItemPriority
import scala.meta.pc.DefinitionResult
import scala.meta.pc.DisplayableException
import scala.meta.pc.HoverSignature
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.Node
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SourcePathMode
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.reports.EmptyReportContext
import scala.meta.pc.reports.ReportContext
import scala.meta.pc.{PcSymbolInformation => IPcSymbolInformation}

import com.google.common.base.Stopwatch
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    buildTargetName: Option[String] = None,
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    logger: Logger = LoggerFactory.getLogger("mtags"),
    folderPath: Option[Path] = None,
    reportsLevel: ReportLevel = ReportLevel.Info,
    completionItemPriority: CompletionItemPriority = (_: String) => 0,
    sourcePath: ju.function.Supplier[ju.List[Path]] = () => Nil.asJava,
    semanticdbFileManager: SemanticdbFileManager = SemanticdbFileManager.EMPTY,
    optReportContext: Option[ReportContext] = None
) extends PresentationCompiler {

  implicit val executionContext: ExecutionContextExecutor = ec

  val scalaVersion = BuildInfo.scalaCompilerVersion

  implicit val reportContext: ReportContext =
    optReportContext.getOrElse(new EmptyReportContext())

  override def withBuildTargetName(
      buildTargetName: String
  ): ScalaPresentationCompiler =
    copy(buildTargetName = Some(buildTargetName))

  override def withReportsLoggerLevel(level: String): PresentationCompiler =
    copy(reportsLevel = ReportLevel.fromString(level))

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  override def withWorkspace(workspace: Path): PresentationCompiler =
    copy(folderPath = Some(workspace))

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withScheduledExecutorService(
      sh: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(sh))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)

  override def withLogger(logger: Logger): PresentationCompiler =
    copy(logger = logger)

  override def withCompletionItemPriority(
      priority: CompletionItemPriority
  ): PresentationCompiler =
    copy(completionItemPriority = priority)

  override def withSemanticdbFileManager(
      semanticdbFileManager: SemanticdbFileManager
  ): PresentationCompiler =
    copy(semanticdbFileManager = semanticdbFileManager)

  override def withReportContext(
      reportContext: ReportContext
  ): PresentationCompiler =
    copy(optReportContext = Some(reportContext))

  override def supportedCodeActions(): util.List[String] = List(
    CodeActionId.ConvertToNamedArguments,
    CodeActionId.ImplementAbstractMembers,
    CodeActionId.ExtractMethod,
    CodeActionId.InlineValue,
    CodeActionId.InsertInferredType,
    CodeActionId.InsertInferredMethod
  ).asJava

  def this() = this(buildTargetIdentifier = "")

  def additionalReportData(): String =
    s"""|Scala version: $scalaVersion
        |Classpath:
        |${classpath
         .map(path => s"$path [${if (path.exists) "exists" else "missing"} ]")
         .mkString(", ")}
        |Options:
        |${options.mkString(" ")}
        |""".stripMargin

  val compilerAccess =
    new ScalaCompilerAccess(
      logger,
      config,
      sh,
      () => new ScalaCompilerWrapper(newCompiler()),
      buildTargetIdentifier
    )(ec)

  // Constants for parallel batch processing
  private val BatchParallelThreshold = 50
  private val BatchCompilerIdleTimeoutMillis = 5 * 60 * 1000L // 5 minutes

  // Additional compiler instances for parallel batch processing (lazy, with idle shutdown)
  @volatile private var batchCompilerPool: Array[ScalaCompilerAccess] = _
  @volatile private var lastBatchUseTime: Long = 0L
  private val batchPoolLock = new Object

  private def getOrCreateBatchPool(): Array[ScalaCompilerAccess] =
    batchPoolLock.synchronized {
      val instanceCount = config.batchSemanticdbCompilerInstances()
      if (
        batchCompilerPool == null || batchCompilerPool.length != instanceCount - 1
      ) {
        // Shutdown old pool if size changed
        shutdownBatchPool()
        // Create N-1 additional instances (the main compilerAccess is also used)
        logger.debug(
          s"[$buildTargetIdentifier] Creating batch compiler pool with ${instanceCount - 1} additional instances"
        )
        batchCompilerPool = (0 until (instanceCount - 1)).map { i =>
          new ScalaCompilerAccess(
            logger,
            config,
            sh,
            () => new ScalaCompilerWrapper(newCompiler()),
            s"$buildTargetIdentifier-batch-$i"
          )(ec)
        }.toArray
      }
      lastBatchUseTime = System.currentTimeMillis()
      scheduleIdleShutdown()
      batchCompilerPool
    }

  private def scheduleIdleShutdown(): Unit = {
    sh.foreach(
      _.schedule(
        new Runnable {
          def run(): Unit = {
            val idleTime = System.currentTimeMillis() - lastBatchUseTime
            if (idleTime >= BatchCompilerIdleTimeoutMillis) {
              logger.info(
                s"[$buildTargetIdentifier] Shutting down idle batch compiler pool"
              )
              shutdownBatchPool()
            }
          }
        },
        BatchCompilerIdleTimeoutMillis,
        TimeUnit.MILLISECONDS
      )
    )
  }

  private def shutdownBatchPool(): Unit = batchPoolLock.synchronized {
    if (batchCompilerPool != null) {
      logger.debug(
        s"[$buildTargetIdentifier] Shutting down batch compiler pool with ${batchCompilerPool.length} instances"
      )
      batchCompilerPool.foreach(_.shutdown())
      batchCompilerPool = null
    }
  }

  override def shutdown(): Unit = {
    compilerAccess.shutdown()
    shutdownBatchPool()
  }

  def restart(): Unit = {
    compilerAccess.shutdownCurrentCompiler()
  }

  def isLoaded(): Boolean = compilerAccess.isLoaded()

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler =
    newInstance(
      buildTargetIdentifier,
      classpath,
      options,
      () => util.Collections.emptyList()
    )

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String],
      sourcePath: ju.function.Supplier[ju.List[Path]]
  ): PresentationCompiler = {
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala,
      options = options.asScala.toList,
      sourcePath = sourcePath
    )
  }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] = {
    val noDiags = Seq[Diagnostic]().asJava
    if (
      params
        .uri()
        .toString
        .endsWith(".scala") && (config.emitDiagnostics || params
        .shouldReturnDiagnostics())
    ) {
      compilerAccess.withInterruptableCompiler(noDiags, EmptyCancelToken) {
        pc =>
          val mGlobal = pc.compiler()
          pc.didChange(params.uri())
          import mGlobal._
          val sourceFile = new MetalsSourceFile(params)
          metalsAsk[Unit](askReload(List(sourceFile), _))

          if (config.emitDiagnostics)
            // MetalsGlobalThreadNoBackgroundCompilation: safe to compile directly
            mGlobal.diagnosticsOf(sourceFile).asJava
          else
            // MetalsGlobalThread: delegate to the PC thread via askLoadedTyped to avoid race conditions
            mGlobal.onDemandDiagnostics(sourceFile).asJava
      }(emptyQueryContext)
    } else { CompletableFuture.completedFuture(noDiags) }
  }

  def didClose(uri: URI): Unit = {
    compilerAccess.withNonInterruptableCompiler(
      (),
      EmptyCancelToken
    ) { pc =>
      pc.compiler()
        .removeUnitOf(new MetalsSourceFile(uri.toString, Array.empty))
      pc.compiler().richCompilationCache.remove(uri.toString())
    }(emptyQueryContext)
  }

  override def semanticTokens(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Node]] = {
    val empty: ju.List[Node] = new ju.ArrayList[Node]()
    compilerAccess.withInterruptableCompiler(
      empty,
      params.token
    ) { pc =>
      new PcSemanticTokensProvider(
        pc.compiler(params),
        params
      ).provide().asJava
    }(params.toQueryContext)
  }

  override def inlayHints(
      params: InlayHintsParams
  ): CompletableFuture[ju.List[InlayHint]] = {
    val empty: ju.List[InlayHint] = new ju.ArrayList[InlayHint]()
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withInterruptableCompiler(
      empty,
      params.token
    ) { pc =>
      new PcInlayHintsProvider(
        pc.compiler(),
        params
      ).provide().asJava
    }
  }

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] = {
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withInterruptableCompiler(
      EmptyCompletionList(),
      params.token
    ) { pc =>
      new CompletionProvider(pc.compiler(params), params)
        .completions()
    }
  }

  override def codeAction[T](
      params: OffsetParams,
      codeActionId: String,
      codeActionPayload: Optional[T]
  ): CompletableFuture[util.List[TextEdit]] = {
    (codeActionId, codeActionPayload.asScala) match {
      case (
            CodeActionId.ConvertToNamedArguments,
            Some(argIndices: ju.List[_])
          ) =>
        val payload = argIndices.asScala.collect { case i: Integer =>
          i.toInt
        }.toSet
        convertToNamedArguments(params, payload)
      case (CodeActionId.ImplementAbstractMembers, _) =>
        implementAbstractMembers(params)
      case (CodeActionId.InsertInferredType, _) =>
        insertInferredType(params)
      case (CodeActionId.InlineValue, _) =>
        inlineValue(params)
      case (CodeActionId.InsertInferredMethod, _) =>
        insertInferredMethod(params)
      case (CodeActionId.ExtractMethod, Some(extractionPos: OffsetParams)) =>
        params match {
          case range: RangeParams =>
            extractMethod(range, extractionPos)
          case _ =>
            CompletableFuture.failedFuture(
              new IllegalArgumentException(s"Expected range parameters")
            )
        }
      case (id, _) =>
        CompletableFuture.failedFuture(
          new IllegalArgumentException(s"Unsupported action id $id")
        )
    }
  }

  override def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: ju.List[TextEdit] = new ju.ArrayList[TextEdit]()
    implicit val queryInfo = params.toQueryContext
    compilerAccess.withInterruptableCompiler(
      empty,
      params.token
    ) { pc =>
      new CompletionProvider(pc.compiler(params), params).implementAll()
    }
  }

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: ju.List[TextEdit] = new ju.ArrayList[TextEdit]()
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withInterruptableCompiler(
      empty,
      params.token
    ) { pc =>
      new InferredTypeProvider(pc.compiler(params), params)
        .inferredTypeEdits()
        .asJava
    }
  }

  def insertInferredMethod(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: Either[String, List[TextEdit]] = Left(
      "Could not infer method, please report an issue in github.com/scalameta/metals"
    )
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess
      .withInterruptableCompiler(
        empty,
        params.token
      ) { pc =>
        new InferredMethodProvider(pc.compiler(), params)
          .inferredMethodEdits()
      }
      .thenApply {
        case Right(edits: List[TextEdit]) => edits.asJava
        case Left(error: String) => throw new DisplayableException(error)
      }
  }

  override def inlineValue(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: Either[String, List[TextEdit]] = Right(List())
    (compilerAccess
      .withInterruptableCompiler(empty, params.token) { pc =>
        new PcInlineValueProviderImpl(
          pc.compiler(params),
          params
        ).getInlineTextEdits()
      }(params.toQueryContext))
      .thenApply {
        case Right(edits: List[TextEdit]) => edits.asJava
        case Left(error: String) => throw new DisplayableException(error)
      }
  }

  override def extractMethod(
      range: RangeParams,
      extractionPos: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: ju.List[TextEdit] = new ju.ArrayList[TextEdit]()
    implicit val queryInfo: PcQueryContext = range.toQueryContext
    compilerAccess.withInterruptableCompiler(empty, range.token) { pc =>
      new ExtractMethodProvider(
        pc.compiler(range),
        range,
        extractionPos
      ).extractMethod.asJava
    }
  }

  override def convertToNamedArguments(
      params: OffsetParams,
      argIndices: ju.List[Integer]
  ): CompletableFuture[ju.List[TextEdit]] = {
    convertToNamedArguments(
      params,
      argIndices.asScala.map(_.toInt).toSet
    )
  }

  def convertToNamedArguments(
      params: OffsetParams,
      argIndices: Set[Int]
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: Either[String, List[TextEdit]] = Right(List())
    (compilerAccess
      .withInterruptableCompiler(empty, params.token) { pc =>
        new ConvertToNamedArgumentsProvider(
          pc.compiler(params),
          params,
          argIndices
        ).convertToNamedArguments
      }(params.toQueryContext))
      .thenApply {
        case Left(error: String) => throw new DisplayableException(error)
        case Right(edits: List[TextEdit]) => edits.asJava
      }
  }

  override def autoImports(
      name: String,
      params: OffsetParams,
      isExtension: java.lang.Boolean // ignore, because Scala2 doesn't support extension method
  ): CompletableFuture[ju.List[AutoImportsResult]] = {
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withInterruptableCompiler(
      List.empty[AutoImportsResult].asJava,
      params.token
    ) { pc =>
      new AutoImportsProvider(pc.compiler(params), name, params)
        .autoImports()
        .asJava
    }
  }

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] =
    CompletableFuture.completedFuture("")

  // NOTE(olafur): hover and signature help use a "shared" compiler instance because
  // we don't typecheck any sources, we only poke into the symbol table.
  // If we used a shared compiler then we risk hitting `Thread.interrupt`,
  // which can close open `*-sources.jar` files containing Scaladoc/Javadoc strings.
  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] =
    CompletableFuture.completedFuture {
      compilerAccess
        .withNonInterruptableCompiler(item, EmptyCancelToken) { pc =>
          new CompletionItemResolver(pc.compiler()).resolve(item, symbol)
        }(emptyQueryContext)
        .get(1, TimeUnit.SECONDS)
    }

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] = {
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withNonInterruptableCompiler(
      new SignatureHelp(),
      params.token
    ) { pc =>
      new SignatureHelpProvider(pc.compiler(params))
        .signatureHelp(params)
    }
  }

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[ju.Optional[Range]] =
    compilerAccess.withNonInterruptableCompiler(
      Optional.empty[Range](),
      params.token
    ) { pc =>
      new PcRenameProvider(pc.compiler(params), params, None)
        .prepareRename()
        .asJava
    }(params.toQueryContext)

  override def rename(
      params: OffsetParams,
      name: String
  ): CompletableFuture[ju.List[TextEdit]] =
    compilerAccess.withNonInterruptableCompiler(
      List[TextEdit]().asJava,
      params.token
    ) { pc =>
      new PcRenameProvider(
        pc.compiler(params),
        params,
        Some(name)
      ).rename().asJava
    }(params.toQueryContext)

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[HoverSignature]] = {
    implicit val queryInfo: PcQueryContext = params.toQueryContext
    compilerAccess.withNonInterruptableCompiler(
      Optional.empty[HoverSignature](),
      params.token
    ) { pc =>
      Optional.ofNullable(
        new HoverProvider(
          pc.compiler(params),
          params,
          config.hoverContentType()
        )
          .hover()
          .orNull
      )
    }
  }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] = {
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { pc =>
      new PcDefinitionProvider(pc.compiler(params), params)
        .definition()
    }(params.toQueryContext)
  }

  override def info(
      symbol: String
  ): CompletableFuture[Optional[IPcSymbolInformation]] = {
    compilerAccess.withNonInterruptableCompiler[Optional[IPcSymbolInformation]](
      Optional.empty(),
      EmptyCancelToken
    ) { pc =>
      val result: Option[IPcSymbolInformation] =
        pc.compiler().info(symbol).map(_.asJava)
      result.asJava
    }(emptyQueryContext)
  }

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] = {
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { pc =>
      new PcDefinitionProvider(pc.compiler(params), params)
        .typeDefinition()
    }(params.toQueryContext)
  }

  override def documentHighlight(
      params: OffsetParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    compilerAccess.withInterruptableCompiler(
      List.empty[DocumentHighlight].asJava,
      params.token()
    ) { pc =>
      new PcDocumentHighlightProvider(pc.compiler(params), params)
        .highlights()
        .asJava
    }(params.toQueryContext)

  override def references(
      params: ReferencesRequest
  ): CompletableFuture[ju.List[ReferencesResult]] = {
    compilerAccess.withInterruptableCompiler(
      List.empty[ReferencesResult].asJava,
      params.file.token()
    ) { pc =>
      val res: List[ReferencesResult] =
        PcReferencesProvider(pc.compiler(), params).references()
      res.asJava
    }(params.file.toQueryContext)
  }

  /**
   * Batch the semanticdb text documents for the given parameters. This method
   * may return partial results if the timeout is reached.
   *
   * The default timeout is chosen to be smaller than the default PC timeout of 20s,
   * which is set as wall-clock time at the level of the compiler job queue.
   *
   * @param params the list of virtual file parameters
   * @param timeout the timeout after which partial results may be returned
   * @return the serialized TextDocuments protobuf
   */
  override def batchSemanticdbTextDocuments(
      params: ju.List[VirtualFileParams],
      timeout: Duration
  ): CompletableFuture[Array[Byte]] = {
    val instanceCount = config.batchSemanticdbCompilerInstances()

    if (params.isEmpty()) {
      CompletableFuture.completedFuture(Array.emptyByteArray)
    } else if (instanceCount <= 1 || params.size() <= BatchParallelThreshold) {
      // Sequential: use existing compilerAccess (current behavior)
      processBatchSequential(params, timeout)
    } else {
      // Parallel: distribute across main + pool instances
      processBatchParallel(params, timeout)
    }
  }

  /**
   * Process a sequence of files with the given provider, respecting the timeout.
   * Returns the processed documents.
   */
  private def processFiles(
      files: Seq[VirtualFileParams],
      provider: SemanticdbTextDocumentProvider,
      timer: Stopwatch,
      timeout: Duration,
      logContext: String
  ): Seq[s.TextDocument] = {
    val docsBuffer = mutable.ListBuffer.empty[s.TextDocument]
    val filesIterator = files.iterator
    var timedOut = false

    while (filesIterator.hasNext && !timedOut) {
      if (timer.elapsed().compareTo(timeout) >= 0) {
        timedOut = true
        logger.info(
          s"batchSemanticdbTextDocuments: timeout reached after $timer$logContext, " +
            s"returning partial results (${docsBuffer.size}/${files.size} documents)"
        )
      } else {
        val fileParam = filesIterator.next()
        // we overwrite the URI because the provider relativizes the URI to the workspace path
        try {
          docsBuffer += provider
            .textDocument(fileParam.uri(), fileParam.text())
            .withUri(fileParam.uri().toString())
        } catch {
          case NonFatal(e) =>
            logger.error(
              s"error getting text document for ${fileParam.uri()}",
              e
            )
            docsBuffer += s.TextDocument.defaultInstance.withUri(
              fileParam.uri().toString()
            )
        }
      }
    }
    docsBuffer.toSeq
  }

  /**
   * Process batch sequentially using the main compilerAccess.
   */
  private def processBatchSequential(
      params: ju.List[VirtualFileParams],
      timeout: Duration
  ): CompletableFuture[Array[Byte]] = {
    val param = params.get(0)

    compilerAccess.withInterruptableCompiler(
      Array.emptyByteArray,
      param.token()
    ) { pc =>
      val provider = new SemanticdbTextDocumentProvider(
        pc.compiler(),
        config.semanticdbCompilerOptions().asScala.toList
      )
      val timer = Stopwatch.createStarted()
      val docs =
        processFiles(params.asScala.toSeq, provider, timer, timeout, "")
      s.TextDocuments(docs.toList).toByteArray
    }(param.toQueryContext)
  }

  /**
   * Process batch in parallel using multiple compiler instances.
   * Partitions work across the main compilerAccess and the batch pool.
   */
  private def processBatchParallel(
      params: ju.List[VirtualFileParams],
      timeout: Duration
  ): CompletableFuture[Array[Byte]] = {
    val pool = getOrCreateBatchPool()
    val allCompilers: Array[ScalaCompilerAccess] =
      Array(compilerAccess) ++ pool // Main + pool
    val paramsSeq = params.asScala.toSeq
    val chunkSize =
      Math.ceil(paramsSeq.size.toDouble / allCompilers.length).toInt
    val chunks = paramsSeq.grouped(chunkSize).toSeq

    // Use first param for query context
    val param = params.get(0)
    implicit val queryContext: PcQueryContext = param.toQueryContext

    // Shared timer across all chunks
    val timer = Stopwatch.createStarted()

    // Process each chunk on a different compiler
    val futures =
      chunks.zipWithIndex.map { case (chunk, i) =>
        val compiler = allCompilers(i)
        FutureConverters.toScala(
          compiler.withInterruptableCompiler(
            Seq.empty[s.TextDocument],
            param.token()
          ) { pc =>
            val provider = new SemanticdbTextDocumentProvider(
              pc.compiler(),
              config.semanticdbCompilerOptions().asScala.toList
            )
            processFiles(chunk, provider, timer, timeout, s" in chunk $i")
          }
        )
      }

    FutureConverters
      .toJava(
        Future.sequence(futures).map { docs =>
          val allDocs = docs.flatten
          logger.debug(
            s"batchSemanticdbTextDocuments: parallel processed ${allDocs.size} documents"
          )
          s.TextDocuments(allDocs).toByteArray
        }
      )
      .toCompletableFuture
  }

  override def semanticdbTextDocument(
      fileUri: URI,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    val virtualFile = CompilerVirtualFileParams(fileUri, code)
    semanticdbTextDocument(virtualFile)
  }

  override def semanticdbTextDocument(
      virtualFile: VirtualFileParams
  ): CompletableFuture[Array[Byte]] = {
    compilerAccess.withInterruptableCompiler(
      Array.emptyByteArray,
      EmptyCancelToken
    ) { pc =>
      new SemanticdbTextDocumentProvider(
        pc.compiler(virtualFile),
        config.semanticdbCompilerOptions().asScala.toList
      )
        .textDocument(virtualFile.uri(), virtualFile.text())
        .toByteArray
    }(virtualFile.toQueryContext)
  }

  override def selectionRange(
      params: ju.List[OffsetParams]
  ): CompletableFuture[ju.List[SelectionRange]] = {
    CompletableFuture.completedFuture {
      compilerAccess.withSharedCompiler(
        List.empty[SelectionRange].asJava
      ) { pc =>
        new SelectionRangeProvider(pc.compiler(), params)
          .selectionRange()
          .asJava
      }(
        params.asScala.headOption
          .map(_.toQueryContext)
          .getOrElse(emptyQueryContext)
      )
    }
  }

  override def buildTargetId(): String = buildTargetIdentifier

  def newCompiler(): MetalsGlobal = {
    val classpath = this.classpath.mkString(File.pathSeparator)
    val vd = new VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.Ymacroexpand.value = "discard"
    settings.YpresentationAnyThread.value = true
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath

    if (config.sourcePathMode() != SourcePathMode.DISABLED) {
      settings.sourcepath.value =
        sourcePath.get().asScala.mkString(File.pathSeparator)
    }
    settings.verbose.value = reportsLevel.isVerbose

    logger.debug(
      s"[$buildTargetIdentifier] using source path mode: ${config.sourcePathMode()}"
    )

    val rootSrcPackage = SimpleTimer.timedThunk(
      s"[$buildTargetIdentifier] collect logical packages",
      thresholdMillis = 1000
    ) {
      if (config.sourcePathMode() == SourcePathMode.MBT) {
        ParsedLogicalPackage.fromMbtIndex(
          semanticdbFileManager.listAllPackages()
        )
      } else {
        val packages = semanticdbFileManager.listAllPackages().asScala
        val paths: Set[Path] = settings.sourcepath.value
          .split(File.pathSeparator)
          .filter(_.nonEmpty)
          .map(Paths.get(_))
          .toSet
        val indexFiles: Set[Path] =
          packages.values.flatMap(_.asScala).toSet

        val filteredPackages =
          packages.mapValues(ps => ps.asScala.filter(paths.contains).asJava)
        val rootPkg =
          ParsedLogicalPackage.fromMbtIndex(filteredPackages.toMap.asJava)

        val missingFromIndex = paths -- indexFiles
        if (missingFromIndex.nonEmpty) {
          logger.info(
            s"[$buildTargetIdentifier] Parsing ${missingFromIndex.size} additional source path files not in MBT index"
          )
          val augmentSettings = new Settings
          augmentSettings.usejavacp.value = settings.usejavacp.value
          augmentSettings.classpath.value = settings.classpath.value
          augmentSettings.sourcepath.value =
            missingFromIndex.mkString(File.pathSeparator)
          augmentSettings.bootclasspath.value = settings.bootclasspath.value
          val augmentedPackages =
            ParsedLogicalPackage.collectLogicalPackages(augmentSettings)
          rootPkg.mergeWith(augmentedPackages)
        }

        rootPkg
      }
    }
    if (rootSrcPackage.packages.isEmpty && rootSrcPackage.sources.isEmpty) {
      logger.warn(
        s"[$buildTargetIdentifier] no logical packages found in source path (mode: ${config.sourcePathMode()})"
      )
    }
    if (reportsLevel.isVerbose)
      logger.trace(
        s"[$buildTargetIdentifier] source path: ${rootSrcPackage.prettyPrint()}"
      )
    if (
      !BuildInfo.scalaCompilerVersion.startsWith("2.11") &&
      BuildInfo.scalaCompilerVersion != "2.12.4"
    ) {
      settings.processArguments(
        List("-Ycache-plugin-class-loader:last-modified"),
        processAll = true
      )
    }
    if (classpath.isEmpty) {
      settings.usejavacp.value = true
    }
    val (isSuccess, unprocessed) =
      settings.processArguments(options, processAll = true)
    if (unprocessed.nonEmpty || !isSuccess) {
      logger.warn(s"Unknown compiler options: ${unprocessed.mkString(", ")}")
    }
    // settings are needed during the constructor of interactive.Global
    // and the reporter doesn't have a reference to global yet
    val reporter = new MetalsReporter(settings)
    val mg = new MetalsGlobal(
      settings,
      reporter,
      search,
      buildTargetIdentifier,
      config,
      folderPath,
      completionItemPriority,
      rootSrcPackage
    )
    reporter._metalsGlobal = mg
    mg
  }

  // ================
  // Internal methods
  // ================

  override def diagnosticsForDebuggingPurposes(): util.List[String] = {
    Nil.asJava
  }

  implicit class XtensionParams(params: VirtualFileParams) {
    def toQueryContext: PcQueryContext =
      PcQueryContext(Some(params), additionalReportData)

  }

  def emptyQueryContext: PcQueryContext =
    PcQueryContext(None, additionalReportData)

}
