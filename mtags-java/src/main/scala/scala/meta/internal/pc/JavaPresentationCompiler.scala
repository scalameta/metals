package scala.meta.internal.pc

import java.lang
import java.net.URI
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters._

import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.HoverSignature
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams
import scala.meta.pc.ReferencesRequest
import scala.meta.pc.ReferencesResult
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

case class JavaPresentationCompiler(
    buildTargetIdentifier: String = "",
    classpath: Seq[Path] = Nil,
    options: List[String] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None
) extends PresentationCompiler {

  private val javaCompiler = new JavaMetalsGlobal(search, config, classpath)

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    CompletableFuture.completedFuture(
      new JavaCompletionProvider(
        javaCompiler,
        params,
        config.isCompletionSnippetsEnabled,
        buildTargetIdentifier
      ).completions()
    )

  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] = CompletableFuture.completedFuture(item)

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture(
      new JavaSignatureHelpProvider(javaCompiler, params).signatureHelp()
    )

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[HoverSignature]] =
    CompletableFuture.completedFuture(
      Optional.ofNullable(
        new JavaHoverProvider(javaCompiler, params, config.hoverContentType())
          .hover()
          .orNull
      )
    )

  override def rename(
      params: OffsetParams,
      name: String
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(
      new JavaRenameProvider(javaCompiler, params, Some(name)).rename().asJava
    )

  override def definition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    CompletableFuture.completedFuture(
      new JavaDefinitionProvider(javaCompiler, params).definition()
    )

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    CompletableFuture.completedFuture(
      new JavaDefinitionProvider(javaCompiler, params).typeDefinition()
    )

  override def documentHighlight(
      params: OffsetParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CompletableFuture.completedFuture(
      new JavaDocumentHighlightProvider(javaCompiler, params)
        .documentHighlight()
        .asJava
    )

  override def references(
      params: ReferencesRequest
  ): CompletableFuture[util.List[ReferencesResult]] =
    CompletableFuture.completedFuture(
      new JavaReferencesProvider(javaCompiler, params)
        .references()
        .asJava
    )

  override def getTasty(
      targetUri: URI,
      isHttpEnabled: Boolean
  ): CompletableFuture[String] = CompletableFuture.completedFuture("")

  override def autoImports(
      name: String,
      params: OffsetParams,
      isExtension: lang.Boolean
  ): CompletableFuture[util.List[AutoImportsResult]] =
    CompletableFuture.completedFuture(
      new JavaAutoImportsProvider(
        javaCompiler,
        params,
        name,
        buildTargetIdentifier
      ).autoImports().asJava
    )

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
    CompletableFuture.completedFuture(Nil.asJava)

  override def didChange(
      params: VirtualFileParams
  ): CompletableFuture[util.List[Diagnostic]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def didClose(uri: URI): Unit = ()

  override def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] =
    CompletableFuture.completedFuture(Array.emptyByteArray)

  override def selectionRange(
      params: util.List[OffsetParams]
  ): CompletableFuture[util.List[SelectionRange]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def shutdown(): Unit = ()

  override def restart(): Unit = ()

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

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
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler =
    copy(
      buildTargetIdentifier = buildTargetIdentifier,
      classpath = classpath.asScala.toSeq,
      options = options.asScala.toList
    )

  override def diagnosticsForDebuggingPurposes(): util.List[String] = Nil.asJava

  override def isLoaded: Boolean = true

  override def scalaVersion(): String = "java"

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[Optional[lsp4j.Range]] =
    CompletableFuture.completedFuture(
      new JavaRenameProvider(javaCompiler, params, None).prepareRename()
    )
}
