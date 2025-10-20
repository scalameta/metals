package scala.meta.internal.jpc

import java.lang
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.function.Supplier
import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters._
import scala.util.Try

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.EmbeddedClient
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
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

case class JavaPresentationCompiler(
    override val buildTargetId: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    sourcePath: Supplier[ju.List[Path]] = () => Nil.asJava,
    search: SymbolSearch = EmptySymbolSearch,
    embedded: EmbeddedClient = EmptyEmbeddedClient,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None,
    semanticdbFileManager: SemanticdbFileManager = EmptySemanticdbFileManager,
    logger: Logger = LoggerFactory.getLogger("mtags"),
    reportsLevel: ReportLevel = ReportLevel.Info
) extends PresentationCompiler {

  private val compiler = new JavaPresentationCompilerAccess(
    logger,
    config,
    sh,
    () =>
      new JavaMetalsCompilerWrapper(() =>
        new JavaMetalsCompiler(
          buildTargetId,
          logger,
          search,
          embedded,
          semanticdbFileManager,
          config,
          classpath,
          options
        )
      ),
    s"javac-$buildTargetId"
  )(ec)
  implicit val reportContext: ReportContext =
    workspace
      .map(new StdReportContext(_, _ => Some(buildTargetId), reportsLevel))
      .getOrElse(EmptyReportContext)
  @volatile private var lastParams: VirtualFileParams =
    CompilerVirtualFileParams(
      URI.create("file:///bugreport.java"),
      "",
      EmptyCancelToken
    )

  private def request[T](params: VirtualFileParams, default: T)(
      f: JavaMetalsCompiler => T
  ): CompletableFuture[T] = {
    lastParams = params
    compiler.withInterruptableCompiler(default, params.token()) { pc =>
      f(pc.compiler())
    }(params.toQueryContext)
  }

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    request(params, new CompletionList()) { pc =>
      new JavaCompletionProvider(
        pc,
        params,
        config.isCompletionSnippetsEnabled
      ).completions()
    }

  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] =
    CompletableFuture.completedFuture(item)

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    request(params, new SignatureHelp()) { pc =>
      // new JavaSignatureHelpProvider(
      //   pc,
      //   params,
      //   config.hoverContentType()
      // ).signatureHelp()
      new SignatureHelp()
    }

  override def semanticTokens(
      params: VirtualFileParams
  ): CompletableFuture[util.List[Node]] = {
    request(params, util.Collections.emptyList[Node]()) { pc =>
      // new JavaSemanticTokensProvider(pc, params).semanticTokens()
      util.Collections.emptyList[Node]()
    }
  }

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[HoverSignature]] =
    request(params, Optional.empty[HoverSignature]()) { pc =>
      Optional.ofNullable(
        new JavaHoverProvider(pc, params, config.hoverContentType())
          .hover()
          .orNull
      )
    }

  override def rename(
      params: OffsetParams,
      name: String
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def definition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] = {
    request(params, DefinitionResultImpl.empty) { pc =>
      // new JavaDefinitionProvider(pc, params).definition()
      DefinitionResultImpl.empty
    }
  }

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    request(params, DefinitionResultImpl.empty) { pc =>
      // new JavaDefinitionProvider(pc, params, TypeDefinitionProcessor)
      //   .definition()
      DefinitionResultImpl.empty
    }

  override def documentHighlight(
      params: OffsetParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def references(
      params: ReferencesRequest
  ): CompletableFuture[util.List[ReferencesResult]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] =
    CompletableFuture.completedFuture("")

  override def autoImports(
      name: String,
      params: OffsetParams,
      isExtension: lang.Boolean
  ): CompletableFuture[util.List[AutoImportsResult]] = {
    request(params, util.Collections.emptyList[AutoImportsResult]()) { pc =>
      // new JavaAutoImportProvider(pc, params, name).autoImports().asJava
      util.Collections.emptyList[AutoImportsResult]()
    }
  }

  override def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def extractMethod(
      range: RangeParams,
      extractionPos: OffsetParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def convertToNamedArguments(
      params: OffsetParams,
      argIndices: util.List[Integer]
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def inlayHints(
      params: InlayHintsParams
  ): CompletableFuture[util.List[lsp4j.InlayHint]] =
    // TODO
    CompletableFuture.completedFuture(Nil.asJava)

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[util.List[Diagnostic]] = {
    if (!config.emitDiagnostics) {
      CompletableFuture.completedFuture(Nil.asJava)
    } else {
      request(params, util.Collections.emptyList[Diagnostic]()) { pc =>
        new JavaDiagnosticProvider(pc, params).diagnostics().asJava
      }
    }
  }

  override def supportedCodeActions(): util.List[String] = {
    util.Collections.emptyList()
  }

  override def didClose(uri: URI): Unit = ()

  override def semanticdbTextDocument(
      uri: URI,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    val params = CompilerVirtualFileParams(uri, code, EmptyCancelToken)
    request(params, Array.emptyByteArray) { pc =>
      new JavaSemanticdbProvider(pc).textDocumentBytes(params)
    }
  }

  override def selectionRange(
      params: util.List[OffsetParams]
  ): CompletableFuture[util.List[SelectionRange]] =
    CompletableFuture.completedFuture(
      util.Collections.emptyList[SelectionRange]()
      // new JavaSelectionRangeProvider().provide(params)
    )

  override def shutdown(): Unit = {
    compiler.shutdown()
  }

  override def restart(): Unit = {
    logger.info("javapc: restarting")
    compiler.shutdownCurrentCompiler()
  }

  override def withReportsLoggerLevel(level: String): PresentationCompiler =
    copy(reportsLevel = ReportLevel.fromString(level))

  override def withLogger(logger: Logger): PresentationCompiler = {
    copy(logger = logger)
  }

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  override def withEmbeddedClient(
      embedded: EmbeddedClient
  ): PresentationCompiler =
    copy(embedded = embedded)

  override def withSemanticdbFileManager(
      semanticdbFileManager: SemanticdbFileManager
  ): PresentationCompiler =
    copy(semanticdbFileManager = semanticdbFileManager)

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withScheduledExecutorService(
      scheduledExecutorService: ScheduledExecutorService
  ): PresentationCompiler = copy(sh = Some(scheduledExecutorService))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler = copy(config = config)

  override def withWorkspace(workspace: Path): PresentationCompiler =
    copy(workspace = Some(workspace))

  override def newInstance(
      buildTargetId: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler =
    newInstance(
      buildTargetId,
      classpath,
      options,
      sourcePath = () => Nil.asJava
    )

  override def newInstance(
      buildTargetId: String,
      classpath: util.List[Path],
      options: util.List[String],
      sourcePath: util.function.Supplier[util.List[Path]]
  ): PresentationCompiler =
    copy(
      buildTargetId = buildTargetId,
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList,
      sourcePath = sourcePath
    )

  override def diagnosticsForDebuggingPurposes(): util.List[String] = Nil.asJava

  override def isLoaded: Boolean = true

  override def scalaVersion(): String = "java"

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[Optional[lsp4j.Range]] =
    CompletableFuture.completedFuture(Optional.empty())

  implicit class XtensionParams(params: VirtualFileParams) {
    def toQueryContext: PcQueryContext =
      PcQueryContext(Some(params), additionalReportData)
  }

  def additionalReportData(): String = {
    val debugClasspath = classpath
      .map(path =>
        s"$path [${if (Files.exists(path)) "exists" else "missing"} ]"
      )
      .mkString(", ")
    val debugOptions =
      Try(
        new JavaPruneCompiler(logger, semanticdbFileManager, embedded)
          .compileOptions(PruneJavaFile.simple(lastParams), classpath, options)
      )
        .getOrElse(options)
    s"""|Scala version: $scalaVersion
        |Classpath:
        |${debugClasspath}
        |Options:
        |${debugOptions.mkString(" ")}
        |""".stripMargin
  }
}
