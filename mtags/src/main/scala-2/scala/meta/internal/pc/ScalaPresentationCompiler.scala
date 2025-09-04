package scala.meta.internal.pc

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import java.{util => ju}

import scala.collection.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.EmptyReportContext
import scala.meta.internal.metals.PcQueryContext
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments._
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
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.{PcSymbolInformation => IPcSymbolInformation}

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    buildTargetName: Option[String] = None,
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    folderPath: Option[Path] = None,
    reportsLevel: ReportLevel = ReportLevel.Info,
    completionItemPriority: CompletionItemPriority = (_: String) => 0,
    sourcePath: Seq[Path] = Nil
) extends PresentationCompiler {

  implicit val executionContext: ExecutionContextExecutor = ec

  val scalaVersion = BuildInfo.scalaCompilerVersion

  val logger: Logger =
    Logger.getLogger(classOf[ScalaPresentationCompiler].getName)

  implicit val reportContex: ReportContext =
    folderPath
      .map(new StdReportContext(_, _ => buildTargetName, reportsLevel))
      .getOrElse(EmptyReportContext)

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

  override def withCompletionItemPriority(
      priority: CompletionItemPriority
  ): PresentationCompiler =
    copy(completionItemPriority = priority)

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
      config,
      sh,
      () => new ScalaCompilerWrapper(newCompiler()),
      buildTargetIdentifier
    )(ec)

  override def shutdown(): Unit = {
    compilerAccess.shutdown()
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
      util.Collections.emptyList()
    )

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String],
      sourcePath: util.List[Path]
  ): PresentationCompiler = {
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala,
      options = options.asScala.toList,
      sourcePath = sourcePath.asScala
    )
  }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] = {
    val noDiags = Seq[Diagnostic]().asJava
    if (config.emitDiagnostics) {
      compilerAccess.withInterruptableCompiler(noDiags, EmptyCancelToken) {
        pc =>
          val mGlobal = pc.compiler()
          import mGlobal._

          val sourceFile = new MetalsSourceFile(params)
          metalsAsk[Unit](askReload(List(sourceFile), _))

          val diags = mGlobal.diagnosticsOf(sourceFile)
          diags.asJava
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
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
    if (sourcePath.nonEmpty) {
      logger.info(
        s"[$buildTargetIdentifier]: sourcepath: ${sourcePath.mkString(File.pathSeparator)}"
      )
      settings.sourcepath.value = sourcePath.mkString(File.pathSeparator)
    }
    settings.YpresentationAnyThread.value = true
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
      logger.warning(s"Unknown compiler options: ${unprocessed.mkString(", ")}")
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
      completionItemPriority
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
