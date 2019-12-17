package scala.meta.internal.pc

import java.io.File
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.logging.Logger
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import scala.meta.internal.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.mtags.BuildInfo
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.reporters.StoreReporter
import scala.meta.pc.PresentationCompilerConfig
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.Location
import scala.meta.pc.DefinitionResult
import scala.collection.Seq
import java.{util => ju}
import scala.meta.pc.AutoImportsResult

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl()
) extends PresentationCompiler {
  val logger: Logger =
    Logger.getLogger(classOf[ScalaPresentationCompiler].getName)
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

  val access = new CompilerAccess(config, sh, () => newCompiler())(ec)
  override def shutdown(): Unit = {
    access.shutdown()
  }

  override def restart(): Unit = {
    access.shutdownCurrentCompiler()
  }

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

  def emptyCompletion: CompletionList = {
    val items = new CompletionList(Nil.asJava)
    items.setIsIncomplete(true)
    items
  }

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    access.withInterruptableCompiler(emptyCompletion, params.token) { global =>
      new CompletionProvider(global, params).completions()
    }

  override def autoImports(
      name: String,
      params: OffsetParams
  ): CompletableFuture[ju.List[AutoImportsResult]] =
    access.withInterruptableCompiler(
      List.empty[AutoImportsResult].asJava,
      params.token
    ) { global =>
      new AutoImportsProvider(global, name, params).autoImports().asJava
    }

  // NOTE(olafur): hover and signature help use a "shared" compiler instance because
  // we don't typecheck any sources, we only poke into the symbol table.
  // If we used a shared compiler then we risk hitting `Thread.interrupt`,
  // which can close open `*-sources.jar` files containing Scaladoc/Javadoc strings.
  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] = CompletableFuture.completedFuture {
    access.withSharedCompiler(item) { global =>
      new CompletionItemResolver(global).resolve(item, symbol)
    }
  }

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    access.withNonInterruptableCompiler(
      new SignatureHelp(),
      params.token
    ) { global =>
      new SignatureHelpProvider(global).signatureHelp(params)
    }

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[java.util.List[Location]] = {
    access.withNonInterruptableCompiler(
      List[Location]().asJava,
      params.token
    ) { global =>
      new TypeDefinitionProvider(global).typeDefinition(params).asJava
    }
  }

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[Hover]] =
    access.withNonInterruptableCompiler(
      Optional.empty[Hover](),
      params.token
    ) { global =>
      Optional.ofNullable(new HoverProvider(global, params).hover().orNull)
    }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] = {
    access.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { global =>
      new PcDefinitionProvider(global, params).definition()
    }
  }

  override def semanticdbTextDocument(
      filename: String,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    access.withInterruptableCompiler(
      Array.emptyByteArray,
      EmptyCancelToken
    ) { global =>
      new SemanticdbTextDocumentProvider(global)
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
    if (!BuildInfo.scalaCompilerVersion.startsWith("2.11") &&
      BuildInfo.scalaCompilerVersion != "2.12.4") {
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
    require(isSuccess, unprocessed)
    require(unprocessed.isEmpty, unprocessed)
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
    access.reporter
      .asInstanceOf[StoreReporter]
      .infos
      .iterator
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
}
