package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.{util as ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters.*

import scala.meta.internal.metals.ReportLevel
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.HoverSignature
import scala.meta.pc.Node
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams
import scala.meta.pc.ReportContext
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams
import scala.meta.pc.VirtualFileParams

import dotty.tools.pc.{ScalaPresentationCompiler as DottyPresentationCompiler}
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextEdit

/**
 * This is a wrapper around the DottyPresentationCompiler
 * The main purpose is to allow metals developers to iterate
 * faster on new features.
 *
 * All bugfixes should happen directly in dotty repository.
 * https://github.com/lampepfl/dotty/tree/main/presentation-compiler
 *
 * New features can be developed here, and then later moved to dotty.
 */
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
    additionalReportContexts: List[ReportContext] = Nil,
) extends PresentationCompiler:
  val underlying: PresentationCompiler =
    val pc = new DottyPresentationCompiler(
      buildTargetIdentifier = buildTargetIdentifier,
      buildTargetName = buildTargetName,
      classpath = classpath,
      options = options,
      search = search,
      ec = ec,
      sh = sh,
      config = config,
      folderPath = folderPath,
      reportsLevel = reportsLevel,
    )
    pc.withAdditionalReportContexts(additionalReportContexts.asJava)

  def this() = this("", None, Nil, Nil)

  override def syntheticDecorations(
      params: SyntheticDecorationsParams
  ): CompletableFuture[ju.List[SyntheticDecoration]] =
    underlying.syntheticDecorations(params)

  override def didClose(uri: URI): Unit =
    underlying.didClose(uri)

  override def isLoaded(): Boolean =
    underlying.isLoaded()

  override def autoImports(
      name: String,
      params: OffsetParams,
      isExtension: java.lang.Boolean,
  ): CompletableFuture[ju.List[AutoImportsResult]] =
    underlying.autoImports(name, params, isExtension)

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: ju.List[Path],
      options: ju.List[String],
  ): PresentationCompiler =
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList,
    )

  override def withScheduledExecutorService(
      scheduledExecutorService: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(scheduledExecutorService))

  override def hover(
      params: OffsetParams
  ): CompletableFuture[ju.Optional[HoverSignature]] =
    underlying.hover(params)

  override def convertToNamedArguments(
      params: OffsetParams,
      argIndices: ju.List[Integer],
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.convertToNamedArguments(params, argIndices)

  override def shutdown(): Unit =
    underlying.shutdown()

  override def withWorkspace(workspace: Path): PresentationCompiler =
    copy(folderPath = Some(workspace))

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    underlying.complete(params)

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)

  override def insertInferredType(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.insertInferredType(params)

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    underlying.typeDefinition(params)

  override def completionItemResolve(
      item: CompletionItem,
      symbol: String,
  ): CompletableFuture[CompletionItem] =
    underlying.completionItemResolve(item, symbol)

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[ju.Optional[org.eclipse.lsp4j.Range]] =
    underlying.prepareRename(params)

  override def diagnosticsForDebuggingPurposes(): ju.List[String] =
    underlying.diagnosticsForDebuggingPurposes()

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  override def scalaVersion(): String =
    underlying.scalaVersion()

  override def definition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    underlying.definition(params)

  override def documentHighlight(
      params: OffsetParams
  ): CompletableFuture[ju.List[DocumentHighlight]] =
    underlying.documentHighlight(params)

  override def rename(
      params: OffsetParams,
      name: String,
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.rename(params, name)

  override def implementAbstractMembers(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.implementAbstractMembers(params)

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Diagnostic]] =
    underlying.didChange(params)

  override def semanticdbTextDocument(
      filename: URI,
      code: String,
  ): CompletableFuture[Array[Byte]] =
    underlying.semanticdbTextDocument(filename, code)

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    underlying.signatureHelp(params)

  override def selectionRange(
      params: ju.List[OffsetParams]
  ): CompletableFuture[ju.List[SelectionRange]] =
    underlying.selectionRange(params)

  override def extractMethod(
      range: RangeParams,
      extractionPos: OffsetParams,
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.extractMethod(range, extractionPos)

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean,
  ): CompletableFuture[String] =
    underlying.getTasty(targetUri, isHttpEnabled)

  override def restart(): Unit =
    underlying.restart()

  override def withExecutorService(
      executorService: ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withBuildTargetName(
      buildTargetName: String
  ): PresentationCompiler =
    copy(buildTargetIdentifier = buildTargetName)

  override def semanticTokens(
      params: VirtualFileParams
  ): CompletableFuture[ju.List[Node]] =
    underlying.semanticTokens(params)

  override def withReportsLoggerLevel(level: String): PresentationCompiler =
    copy(reportsLevel = ReportLevel.fromString(level))

  override def withAdditionalReportContexts(
      additionalReportContexts: ju.List[ReportContext]
  ): PresentationCompiler =
    copy(additionalReportContexts = additionalReportContexts.asScala.toList)

  override def inlineValue(
      params: OffsetParams
  ): CompletableFuture[ju.List[TextEdit]] =
    underlying.inlineValue(params)

end ScalaPresentationCompiler
