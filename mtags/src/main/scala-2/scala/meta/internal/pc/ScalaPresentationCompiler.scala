package scala.meta.internal.pc

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.logging.Logger
import java.{util => ju}

import scala.collection.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.ClassFinder
import scala.meta.internal.metals.DocumentSymbolProvider
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.FoldingRangeProvider
import scala.meta.internal.metals.MultilineStringFormattingProvider
import scala.meta.internal.metals.Trees
import scala.meta.internal.mtags.BuildInfo
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl()
) extends PresentationCompiler {
  implicit val executionContext: ExecutionContextExecutor = ec

  val logger: Logger =
    Logger.getLogger(classOf[ScalaPresentationCompiler].getName)

  val trees = new Trees
  val foldingRangeProvider =
    new FoldingRangeProvider(trees, config.isFoldOnlyLines())
  val documentSymbolProvider = new DocumentSymbolProvider(trees)

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

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

  def this() = this(buildTargetIdentifier = "")

  val compilerAccess =
    new ScalaCompilerAccess(
      config,
      sh,
      () => new ScalaCompilerWrapper(newCompiler())
    )(
      ec
    )

  override def shutdown(): Unit = {
    compilerAccess.shutdown()
  }

  override def restart(): Unit = {
    compilerAccess.shutdownCurrentCompiler()
  }

  def isLoaded(): Boolean = compilerAccess.isLoaded()

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler = {
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala,
      options = options.asScala.toList
    )
  }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] = {
    CompletableFuture.supplyAsync(
      () => trees.didChange(params.uri(), params.text()).asJava,
      ec
    )
  }

  def didClose(uri: URI): Unit = {
    trees.didClose(uri)
  }

  def foldingRange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[FoldingRange]] = {
    CompletableFuture.supplyAsync(
      () => foldingRangeProvider.getRangedFor(params.uri(), params.text()),
      ec
    )
  }

  def documentSymbols(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[DocumentSymbol]] = {
    CompletableFuture.supplyAsync(
      () =>
        documentSymbolProvider
          .documentSymbols(params.uri(), params.text()),
      ec
    )
  }

  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams,
      source: String
  ): CompletableFuture[ju.List[TextEdit]] =
    CompletableFuture.supplyAsync(
      () =>
        MultilineStringFormattingProvider
          .format(params, source, config.isStripMarginOnTypeFormattingEnabled)
          .asJava,
      ec
    )

  def rangeFormatting(
      params: DocumentRangeFormattingParams,
      source: String
  ): CompletableFuture[ju.List[TextEdit]] =
    CompletableFuture.supplyAsync(
      () => MultilineStringFormattingProvider.format(params, source).asJava,
      ec
    )

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    compilerAccess.withInterruptableCompiler(
      EmptyCompletionList(),
      params.token
    ) { pc => new CompletionProvider(pc.compiler, params).completions() }

  override def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] = {
    val empty: ju.List[TextEdit] = new ju.ArrayList[TextEdit]()
    compilerAccess.withInterruptableCompiler(empty, params.token) { pc =>
      new CompletionProvider(pc.compiler, params).implementAll()
    }
  }

  override def autoImports(
      name: String,
      params: OffsetParams
  ): CompletableFuture[ju.List[AutoImportsResult]] =
    compilerAccess.withInterruptableCompiler(
      List.empty[AutoImportsResult].asJava,
      params.token
    ) { pc =>
      new AutoImportsProvider(pc.compiler, name, params).autoImports().asJava
    }

  // NOTE(olafur): hover and signature help use a "shared" compiler instance because
  // we don't typecheck any sources, we only poke into the symbol table.
  // If we used a shared compiler then we risk hitting `Thread.interrupt`,
  // which can close open `*-sources.jar` files containing Scaladoc/Javadoc strings.
  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] =
    CompletableFuture.completedFuture {
      compilerAccess.withSharedCompiler(item) { pc =>
        new CompletionItemResolver(pc.compiler).resolve(item, symbol)
      }
    }

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    compilerAccess.withNonInterruptableCompiler(
      new SignatureHelp(),
      params.token
    ) { pc => new SignatureHelpProvider(pc.compiler).signatureHelp(params) }

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[Hover]] =
    compilerAccess.withNonInterruptableCompiler(
      Optional.empty[Hover](),
      params.token
    ) { pc =>
      Optional.ofNullable(new HoverProvider(pc.compiler, params).hover().orNull)
    }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] = {
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { pc => new PcDefinitionProvider(pc.compiler, params).definition() }
  }

  override def semanticdbTextDocument(
      filename: String,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    compilerAccess.withInterruptableCompiler(
      Array.emptyByteArray,
      EmptyCancelToken
    ) { pc =>
      new SemanticdbTextDocumentProvider(pc.compiler)
        .textDocument(filename, code)
        .toByteArray
    }
  }

  def newCompiler(): MetalsGlobal = {
    val classpath = this.classpath.mkString(File.pathSeparator)
    val vd = new VirtualDirectory("(memory)", None)
    val settings = new Settings
    settings.Ymacroexpand.value = "discard"
    settings.outputDirs.setSingleOutput(vd)
    settings.classpath.value = classpath
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
    new MetalsGlobal(
      settings,
      new StoreReporter,
      search,
      buildTargetIdentifier,
      config
    )
  }

  // ================
  // Internal methods
  // ================

  override def diagnosticsForDebuggingPurposes(): util.List[String] = {
    compilerAccess.reporter.infos.iterator
      .map { info =>
        new StringBuilder()
          .append(info.pos.source.file.path)
          .append(":")
          .append(info.pos.column)
          .append(" ")
          .append(info.msg)
          .append("\n")
          .append(info.pos.lineContent)
          .append("\n")
          .append(info.pos.lineCaret)
          .toString
      }
      .filterNot(_.contains("_CURSOR_"))
      .toList
      .asJava
  }

  override def enclosingClass(
      params: OffsetParams
  ): CompletableFuture[Optional[String]] = {
    CompletableFuture.completedFuture(
      trees
        .get(params.uri(), params.text())
        .map(tree => ClassFinder.findClassForOffset(tree, params))
        .map(Optional.of(_))
        .getOrElse(Optional.empty())
    )
  }
}
