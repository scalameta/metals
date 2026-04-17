package scala.meta.internal.protopc

import java.lang
import java.net.URI
import java.nio.file.Path
import java.util
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.ReportLevel
import scala.meta.internal.pc.DefinitionResultImpl
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.DefinitionResult
import scala.meta.pc.HoverSignature
import scala.meta.pc.InlayHintsParams
import scala.meta.pc.Node
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.ProtobufLspConfig
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Presentation compiler for Protobuf files.
 *
 * Provides LSP features like hover, go-to-definition, completions, and diagnostics
 * for protobuf source files.
 */
case class ProtoPresentationCompiler(
    override val buildTargetId: String = "",
    importPaths: Seq[Path] = Nil,
    search: SymbolSearch = EmptySymbolSearch,
    ec: ExecutionContextExecutor = ExecutionContext.global,
    sh: Option[ScheduledExecutorService] = None,
    config: PresentationCompilerConfig = PresentationCompilerConfigImpl(),
    workspace: Option[Path] = None,
    logger: Logger = LoggerFactory.getLogger("protopc"),
    reportsLevel: ReportLevel = ReportLevel.Info
) extends PresentationCompiler {

  private lazy val compiler = new ProtoMetalsCompiler(
    buildTargetId,
    logger,
    importPaths,
    config
  )

  private def protoConfig: ProtobufLspConfig = config.protobufLspConfig()

  private def request[T](params: VirtualFileParams, default: T)(
      f: ProtoMetalsCompiler => T
  ): CompletableFuture[T] = {
    CompletableFuture.supplyAsync(
      () => {
        try {
          f(compiler)
        } catch {
          case e: Exception =>
            logger
              .error(s"Proto presentation compiler error: ${e.getMessage}", e)
            default
        }
      },
      ec.asInstanceOf[java.util.concurrent.Executor]
    )
  }

  override def complete(
      params: OffsetParams
  ): CompletableFuture[CompletionList] =
    if (!protoConfig.completions()) {
      CompletableFuture.completedFuture(new CompletionList())
    } else {
      request(params, new CompletionList()) { pc =>
        new ProtoCompletionProvider(pc, params, config).completions()
      }
    }

  override def completionItemResolve(
      item: CompletionItem,
      symbol: String
  ): CompletableFuture[CompletionItem] =
    CompletableFuture.completedFuture(item)

  override def signatureHelp(
      params: OffsetParams
  ): CompletableFuture[SignatureHelp] =
    CompletableFuture.completedFuture(new SignatureHelp())

  override def semanticTokens(
      params: VirtualFileParams
  ): CompletableFuture[util.List[Node]] =
    if (!protoConfig.semanticTokens()) {
      CompletableFuture.completedFuture(util.Collections.emptyList[Node]())
    } else {
      request(params, util.Collections.emptyList[Node]()) { pc =>
        new ProtoSemanticTokensProvider(pc, params).semanticTokens()
      }
    }

  override def hover(
      params: OffsetParams
  ): CompletableFuture[Optional[HoverSignature]] =
    if (!protoConfig.hover()) {
      CompletableFuture.completedFuture(Optional.empty[HoverSignature]())
    } else {
      request(params, Optional.empty[HoverSignature]()) { pc =>
        Optional.ofNullable(
          new ProtoHoverProvider(pc, params, config.hoverContentType())
            .hover()
            .orNull
        )
      }
    }

  override def rename(
      params: OffsetParams,
      name: String
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def definition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    if (!protoConfig.definition()) {
      CompletableFuture.completedFuture(DefinitionResultImpl.empty)
    } else {
      request(params, DefinitionResultImpl.empty) { pc =>
        new ProtoDefinitionProvider(pc, params).definition()
      }
    }

  override def typeDefinition(
      params: OffsetParams
  ): CompletableFuture[DefinitionResult] =
    definition(params) // Same as definition for proto

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
  ): CompletableFuture[util.List[AutoImportsResult]] =
    CompletableFuture.completedFuture(
      util.Collections.emptyList[AutoImportsResult]()
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
    if (!config.emitDiagnostics() || !protoConfig.diagnostics()) {
      CompletableFuture.completedFuture(Nil.asJava)
    } else {
      request(params, util.Collections.emptyList[Diagnostic]()) { pc =>
        new ProtoDiagnosticProvider(pc, params).diagnostics().asJava
      }
    }

  override def didClose(uri: URI): Unit = {}

  override def semanticdbTextDocument(
      filename: URI,
      code: String
  ): CompletableFuture[Array[Byte]] = {
    if (!protoConfig.semanticdb()) {
      CompletableFuture.completedFuture(Array.emptyByteArray)
    } else {
      CompletableFuture.supplyAsync(
        () => {
          try {
            val source = new scala.meta.internal.proto.diag.SourceFile(
              filename.toString,
              code
            )
            val file = scala.meta.internal.proto.parse.Parser.parse(source)
            val provider =
              new ProtoSemanticdbProvider(
                filename,
                code,
                file,
                compiler.importResolver
              )
            val doc = provider.textDocument()
            doc.toByteArray
          } catch {
            case e: scala.meta.internal.proto.diag.ProtoError =>
              // Parsing errors are expected for partially-edited/invalid proto files.
              // Avoid spamming error logs during operations like find-refs that may
              // scan many files.
              logger.debug(
                s"Proto semanticdb generation skipped: ${e.getMessage}"
              )
              Array.empty[Byte]
            case e: Exception =>
              logger.error(
                s"Proto semanticdb generation error: ${e.getMessage}",
                e
              )
              Array.empty[Byte]
          }
        },
        ec.asInstanceOf[java.util.concurrent.Executor]
      )
    }
  }

  override def batchSemanticdbTextDocuments(
      params: util.List[VirtualFileParams],
      timeout: java.time.Duration
  ): CompletableFuture[Array[Byte]] = {
    if (params.isEmpty() || !protoConfig.semanticdb()) {
      CompletableFuture.completedFuture(Array.emptyByteArray)
    } else {
      CompletableFuture.supplyAsync(
        () => {
          val docs =
            scala.meta.internal.jsemanticdb.Semanticdb.TextDocuments
              .newBuilder()
          params.asScala.foreach { param =>
            try {
              val source = new scala.meta.internal.proto.diag.SourceFile(
                param.uri().toString,
                param.text()
              )
              val file = scala.meta.internal.proto.parse.Parser.parse(source)
              val provider =
                new ProtoSemanticdbProvider(
                  param.uri(),
                  param.text(),
                  file,
                  compiler.importResolver
                )
              docs.addDocuments(provider.textDocument())
            } catch {
              case e: scala.meta.internal.proto.diag.ProtoError =>
                logger.debug(s"Proto batch semanticdb skipped: ${e.getMessage}")
              case e: Exception =>
                logger.error(
                  s"Proto batch semanticdb error: ${e.getMessage}",
                  e
                )
            }
          }
          docs.build().toByteArray
        },
        ec.asInstanceOf[java.util.concurrent.Executor]
      )
    }
  }

  override def selectionRange(
      params: util.List[OffsetParams]
  ): CompletableFuture[util.List[SelectionRange]] =
    CompletableFuture.completedFuture(Nil.asJava)

  override def shutdown(): Unit = {}

  override def restart(): Unit = {}

  override def diagnosticsForDebuggingPurposes(): util.List[String] =
    Nil.asJava

  override def isLoaded: Boolean = true

  override def scalaVersion(): String = "proto"

  override def prepareRename(
      params: OffsetParams
  ): CompletableFuture[Optional[lsp4j.Range]] =
    CompletableFuture.completedFuture(Optional.empty())

  override def withSearch(search: SymbolSearch): PresentationCompiler =
    copy(search = search)

  override def withExecutorService(
      executorService: java.util.concurrent.ExecutorService
  ): PresentationCompiler =
    copy(ec = ExecutionContext.fromExecutorService(executorService))

  override def withScheduledExecutorService(
      scheduledExecutorService: ScheduledExecutorService
  ): PresentationCompiler =
    copy(sh = Some(scheduledExecutorService))

  override def withConfiguration(
      config: PresentationCompilerConfig
  ): PresentationCompiler =
    copy(config = config)

  override def withWorkspace(workspace: Path): PresentationCompiler =
    copy(workspace = Some(workspace))

  override def newInstance(
      buildTargetIdentifier: String,
      classpath: util.List[Path],
      options: util.List[String]
  ): PresentationCompiler =
    copy(
      buildTargetId = buildTargetIdentifier,
      importPaths = classpath.asScala.toSeq
    )
}
