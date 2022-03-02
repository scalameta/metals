package scala.meta.internal.pc

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.{util as ju}

import scala.collection.JavaConverters.*
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor

import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.mtags.BuildInfo
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.*
import scala.meta.internal.pc.CompilerAccess
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.completions.CompletionsProvider
import scala.meta.pc.*

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.util.*
import org.eclipse.{lsp4j as l}

case class ScalaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None
) extends PresentationCompiler:

  def this() = this("", Nil, Nil)

  import InteractiveDriver.*

  val scalaVersion = BuildInfo.scalaCompilerVersion

  private val forbiddenOptions = Set("-print-lines", "-print-tasty")
  val compilerAccess: CompilerAccess[StoreReporter, InteractiveDriver] =
    Scala3CompilerAccess(
      config,
      sh,
      () => new Scala3CompilerWrapper(newDriver)
    )(
      using ec
    )

  def newDriver: InteractiveDriver =
    val implicitSuggestionTimeout = List("-Ximport-suggestion-timeout", "0")
    val defaultFlags = List("-color:never")
    val filteredOptions = options.filterNot(forbiddenOptions)
    val settings =
      filteredOptions ::: defaultFlags ::: implicitSuggestionTimeout ::: "-classpath" :: classpath
        .mkString(
          File.pathSeparator
        ) :: Nil
    new InteractiveDriver(settings)

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] =
    CompletableFuture.completedFuture {
      TastyUtils.getTasty(targetUri, isHttpEnabled)
    }

  def complete(params: OffsetParams): CompletableFuture[l.CompletionList] =
    compilerAccess.withInterruptableCompiler(
      EmptyCompletionList(),
      params.token
    ) { access =>
      val driver = access.compiler()
      new CompletionsProvider(
        search,
        driver,
        params,
        config,
        buildTargetIdentifier
      ).completions()

    }

  def definition(params: OffsetParams): CompletableFuture[DefinitionResult] =
    compilerAccess.withNonInterruptableCompiler(
      DefinitionResultImpl.empty,
      params.token
    ) { access =>
      val driver = access.compiler()
      PcDefinitionProvider(driver, params, search).definitions()
    }

  def shutdown(): Unit =
    compilerAccess.shutdown()

  def restart(): Unit =
    compilerAccess.shutdownCurrentCompiler()

  def diagnosticsForDebuggingPurposes(): ju.List[String] =
    List[String]().asJava

  def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] =
    compilerAccess.withNonInterruptableCompiler(
      Array.empty[Byte],
      EmptyCancelToken
    ) { access =>
      val driver = access.compiler()
      val provider = SemanticdbTextDocumentProvider(driver, workspace)
      provider.textDocument(filename, code)
    }

  // TODO NOT IMPLEMENTED
  def completionItemResolve(
      item: l.CompletionItem,
      symbol: String
  ): CompletableFuture[l.CompletionItem] =
    CompletableFuture.completedFuture(
      null
    )

  def autoImports(
      name: String,
      params: scala.meta.pc.OffsetParams
  ): CompletableFuture[
    ju.List[scala.meta.pc.AutoImportsResult]
  ] =
    compilerAccess.withNonInterruptableCompiler(
      List.empty[scala.meta.pc.AutoImportsResult].asJava,
      params.token
    ) { access =>
      val driver = access.compiler()
      new AutoImportsProvider(
        search,
        driver,
        name,
        params,
        config,
        buildTargetIdentifier
      )
        .autoImports()
        .asJava
    }

  // TODO NOT IMPLEMENTED
  def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[l.TextEdit]] =
    CompletableFuture.completedFuture(
      List.empty[l.TextEdit].asJava
    )

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[ju.List[l.TextEdit]] =
    val empty: ju.List[l.TextEdit] = new ju.ArrayList[l.TextEdit]()
    compilerAccess.withInterruptableCompiler(empty, params.token) { pc =>
      new InferredTypeProvider(params, pc.compiler(), config)
        .inferredTypeEdits()
        .asJava
    }

  override def selectionRange(
      params: ju.List[OffsetParams]
  ): CompletableFuture[ju.List[l.SelectionRange]] =
    CompletableFuture.completedFuture {
      compilerAccess.withSharedCompiler(List.empty[l.SelectionRange].asJava) {
        pc =>
          new SelectionRangeProvider(
            pc.compiler(),
            params
          ).selectionRange().asJava
      }
    }
  end selectionRange

  def hover(params: OffsetParams): CompletableFuture[ju.Optional[l.Hover]] =
    compilerAccess.withNonInterruptableCompiler(
      ju.Optional.empty[l.Hover](),
      params.token
    ) { access =>
      val driver = access.compiler()
      HoverProvider.hover(params, driver)
    }
  end hover

  def newInstance(
      buildTargetIdentifier: String,
      classpath: ju.List[Path],
      options: ju.List[String]
  ): PresentationCompiler =
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList
    )

  def signatureHelp(params: OffsetParams): CompletableFuture[l.SignatureHelp] =
    compilerAccess.withNonInterruptableCompiler(
      new l.SignatureHelp(),
      params.token
    ) { access =>
      val driver = access.compiler()
      SignatureHelpProvider.contribute(driver, params)
    }

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[l.Diagnostic]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def didClose(uri: URI): Unit =
    compilerAccess.withNonInterruptableCompiler(
      (),
      EmptyCancelToken
    ) { access => access.compiler().close(uri) }

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)

  override def withScheduledExecutorService(
      sh: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(sh))

  def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  def withWorkspace(workspace: Path): PresentationCompiler =
    copy(workspace = Some(workspace))

  override def isLoaded() = compilerAccess.isLoaded()

end ScalaPresentationCompiler
