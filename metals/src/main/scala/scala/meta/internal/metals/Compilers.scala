package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.{meta => m}

import scala.meta.infra.FeatureFlagProvider
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal
import scala.meta.internal.async.CompletableCancelToken
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.CompilerOffsetParamsUtils
import scala.meta.internal.metals.CompilerRangeParamsUtils
import scala.meta.internal.metals.Compilers.PresentationCompilerKey
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.decompile.DecompileBytecode
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.PcSymbolInformation
import scala.meta.internal.worksheets.WorksheetPcData
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken
import scala.meta.pc.CodeActionId
import scala.meta.pc.CompletionItemPriority
import scala.meta.pc.HoverSignature
import scala.meta.pc.JavaFileManagerFactory
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.ProgressBars
import scala.meta.pc.SemanticdbFileManager
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecorationsParams
import scala.meta.pc.VirtualFileParams

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.{Position => LspPosition}
import org.eclipse.lsp4j.{Range => LspRange}
import org.eclipse.lsp4j.{debug => d}
import org.eclipse.{lsp4j => l}

/**
 * Manages lifecycle for presentation compilers in all build targets.
 *
 * We need a custom presentation compiler for each build target since
 * build targets can have different classpaths and compiler settings.
 */
class Compilers(
    workspace: AbsolutePath,
    config: ClientConfiguration,
    serverConfig: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
    buffers: Buffers,
    diagnostics: Diagnostics,
    search: SymbolSearch,
    embedded: Embedded,
    workDoneProgress: WorkDoneProgress,
    sh: ScheduledExecutorService,
    initializeParams: InitializeParams,
    excludedPackages: () => ExcludedPackagesHandler,
    scalaVersionSelector: ScalaVersionSelector,
    trees: Trees,
    mtags: () => Mtags,
    mtagsResolver: MtagsResolver,
    sourceMapper: SourceMapper,
    worksheetProvider: WorksheetProvider,
    completionItemPriority: () => CompletionItemPriority,
    semanticdbFileManager: SemanticdbFileManager,
    javaFileManagerFactory: JavaFileManagerFactory,
    progressBars: ProgressBars,
    timerProvider: TimerProvider,
    featureFlags: FeatureFlagProvider,
)(implicit ec: ExecutionContextExecutorService, rc: ReportContext)
    extends Cancelable {

  val compilerConfiguration = new CompilerConfiguration(
    workspace,
    config,
    userConfig,
    scalaVersionSelector,
    buildTargets,
    buffers,
    embedded,
    progressBars,
    sh,
    initializeParams,
    excludedPackages,
    trees,
    mtags,
    mtagsResolver,
    sourceMapper,
    semanticdbFileManager,
    javaFileManagerFactory,
    featureFlags,
  )
  def fallbackClasspaths = compilerConfiguration.fallbackClasspaths

  import compilerConfiguration._

  private val outlineFilesProvider =
    new OutlineFilesProvider(buildTargets, buffers)

  // Not a TrieMap because we want to avoid loading duplicate compilers for the same build target.
  // Not a `j.u.c.ConcurrentHashMap` because it can deadlock in `computeIfAbsent` when the absent
  // function is expensive, which is the case here.
  val jcache: ju.Map[PresentationCompilerKey, MtagsPresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[PresentationCompilerKey, MtagsPresentationCompiler]
    )
  private val jworksheetsCache
      : ju.Map[AbsolutePath, MtagsPresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[AbsolutePath, MtagsPresentationCompiler]
    )

  private val worksheetsDigests = new TrieMap[AbsolutePath, String]()

  private val cache = jcache.asScala
  private def buildTargetPCFromCache(
      id: BuildTargetIdentifier
  ): Option[PresentationCompiler] =
    cache.get(PresentationCompilerKey.ScalaBuildTarget(id)).collect {
      case lazyPc if lazyPc != null => lazyPc.await
    }

  private val worksheetsCache = jworksheetsCache.asScala

  private var lastPathWithFallbackCompiler: Option[AbsolutePath] = None

  // The "fallback" compiler is used for source files that don't belong to a build target.
  private def fallbackCompiler(path: AbsolutePath): PresentationCompiler = {
    val language = path.toLanguage
    jcache
      .compute(
        PresentationCompilerKey.Default(language),
        (_, value) => {
          val scalaVersion =
            scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)

          Option(value) match {
            case Some(lazyPc) =>
              if (language.isJava) {
                // Always use the same compiler for Java because the fallback
                // Java compiler is not switching between compiler versions like
                // we do for the fallback Scala 2.12/2.13/3 Scala compilers.
                lazyPc
              } else {
                val presentationCompiler = lazyPc.await
                if (presentationCompiler.scalaVersion() == scalaVersion) {
                  lazyPc
                } else {
                  presentationCompiler.shutdown()
                  StandaloneCompiler(
                    scalaVersion,
                    search,
                    fallbackClasspaths.scalaCompilerClasspath(),
                    completionItemPriority(),
                  )
                }
              }
            case None =>
              if (language.isJava) {
                StandaloneJavaCompiler(
                  search,
                  completionItemPriority(),
                  fallbackClasspaths.javaCompilerClasspath(),
                )
              } else {
                StandaloneCompiler(
                  scalaVersion,
                  search,
                  fallbackClasspaths.scalaCompilerClasspath(),
                  completionItemPriority(),
                )
              }
          }
        },
      )
      .await
  }

  def loadedPresentationCompilerCount(): Int =
    cache.values.count(_.await.isLoaded())

  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    Cancelable.cancelEach(worksheetsCache.values)(_.shutdown())
    // important not to leak presentation compilers, they come with
    // a background thread that holds a reference to the compiler
    // without a proper shutdown, the thread and MetalsGlobal stay around
    cache.values.foreach(_.shutdown())
    cache.clear()
    worksheetsCache.clear()
    worksheetsDigests.clear()
    outlineFilesProvider.clear()
  }

  def restartAll(): Unit = {
    val count = cache.size
    cancel()
    scribe.debug(
      s"restarted ${count} presentation compiler${LogMessages.plural(count)}"
    )
  }

  def load(paths: Seq[AbsolutePath]): Future[Unit] =
    if (Testing.isEnabled) Future.successful(())
    else {
      Future {
        val targets = paths
          .filter(_.isScalaFilename)
          .flatMap(path => buildTargets.inverseSources(path).toList)
          .distinct
        targets.foreach { target =>
          loadCompiler(target).foreach { pc =>
            pc
              .hover(
                CompilerOffsetParams(
                  Paths.get("Main.scala").toUri(),
                  "object Ma\n",
                  "object Ma".length(),
                )
              )
              .thenApply(_.map(_.toLsp()))
          }
        }
      }
    }

  def didClose(path: AbsolutePath): Unit = {
    loadCompiler(path).foreach(_.didClose(path.toNIO.toUri()))
  }

  def didFocus(path: AbsolutePath): Future[List[Diagnostic]] = {
    val maybeDiagnostics =
      for (pc <- loadCompiler(path); contents <- buffers.get(path))
        yield {
          timerProvider.timed(
            s"[${pc.buildTargetId()}] computed diagnostics",
            onlyIf = serverConfig.statistics.isDiagnostics,
          ) {
            pc.didChange(
              CompilerVirtualFileParams(
                path.toNIO.toUri,
                contents,
                EmptyCancelToken,
                outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
              )
            ).asScala
              .map { result =>
                result.asScala.toList
              }
          }
        }

    maybeDiagnostics.getOrElse(Future.successful(List.empty))
  }

  private val inFlightDidChange =
    TrieMap.empty[AbsolutePath, CompletableCancelToken]
  private val diagnosticsDebouncerDelay: FiniteDuration =
    if (Testing.isEnabled) 0.millis
    else sys.Prop[Int]("metals.errors-delay").option.getOrElse(500).millis
  private val fileDidChange: BatchedFunction[AbsolutePath, Unit] =
    BatchedFunction.fromFuture[AbsolutePath, Unit](
      changedFiles => {
        for {
          _ <- sh.sleep(diagnosticsDebouncerDelay)
          futures = for {
            file <- changedFiles.distinct
            pc <- this.loadCompiler(file).toList
            contents <- buffers.get(file).toList
          } yield {
            val token = new CompletableCancelToken()
            inFlightDidChange.put(file, token).foreach { old =>
              old.cancel()
            }
            val params = CompilerVirtualFileParams(
              file.toNIO.toUri,
              contents,
              token = token,
            )
            for {
              reportedDiagnostics <- pc
                .didChange(params)
                .asScala
              _ = diagnostics.publishDiagnosticsNotAdjusted(
                file,
                reportedDiagnostics.asScala.toList,
              )
            } yield ()
          }
          _ <- Future.sequence(futures)
        } yield ()
      },
      "fileDidChange",
    )

  private def didChangeBSPDiagnostics(
      path: AbsolutePath,
      shouldReturnDiagnostics: Boolean,
      content: Option[String] = None,
  ): Future[List[Diagnostic]] = {
    def originInput =
      content.map(Input.VirtualFile(path.toURI.toString(), _)).getOrElse {
        path.toInputFromBuffers(buffers)
      }

    loadCompiler(path)
      .map { pc =>
        val inputAndAdjust =
          if (
            path.isWorksheet && ScalaVersions.isScala3Version(
              pc.scalaVersion()
            )
          ) {
            WorksheetProvider.worksheetScala3AdjustmentsForPC(originInput)
          } else {
            None
          }

        val (input, adjust) = inputAndAdjust.getOrElse(
          originInput,
          AdjustedLspData.default,
        )

        outlineFilesProvider.didChange(pc.buildTargetId(), path)

        for {
          ds <-
            pc
              .didChange(
                Compilers.DidChangeCompilerFileParams(
                  path.toNIO.toUri(),
                  input.value,
                  shouldReturnDiagnostics,
                )
              )
              .asScala
        } yield {
          ds.asScala.map(adjust.adjustDiagnostic).toList

        }
      }
      .getOrElse(Future.successful(Nil))
  }

  def didChange(path: AbsolutePath): Future[Unit] = {
    if (userConfig().presentationCompilerDiagnostics)
      // Batch/debounce these requests since they can arrivein bursts
      fileDidChange(Seq(path))
    else
      didChangeBSPDiagnostics(path, shouldReturnDiagnostics = false).ignoreValue
  }

  def didChangeWithDiagnostics(
      path: AbsolutePath,
      content: Option[String] = None,
  ): Future[List[Diagnostic]] = {
    didChangeBSPDiagnostics(path, shouldReturnDiagnostics = true, content)
  }

  def didCompile(report: CompileReport): Unit = {
    val isSuccessful = report.getErrors == 0
    val isBestEffortCompilation =
      buildTargets
        .scalaTarget(report.getTarget)
        .map(_.isBestEffort)
        .getOrElse(false)
    buildTargetPCFromCache(report.getTarget).foreach { pc =>
      if (
        outlineFilesProvider.shouldRestartPc(
          report.getTarget,
          DidCompile(isSuccessful),
        )
      ) {
        pc.restart()
        loadCompiler(report.getTarget)
      }
    }

    outlineFilesProvider.onDidCompile(report.getTarget(), isSuccessful)

    if (isSuccessful || isBestEffortCompilation) {
      // Restart PC for all build targets that depend on this target since the classfiles
      // may have changed.
      for {
        target <- buildTargets.allInverseDependencies(report.getTarget)
        if target != report.getTarget
        if outlineFilesProvider.shouldRestartPc(target, InverseDependency)
        compiler <- buildTargetPCFromCache(target)
      } {
        compiler.restart()
      }
    }
  }

  def restartFallbackCompilers(): Unit = {
    for {
      language <- List(s.Language.SCALA, s.Language.JAVA)
      compiler <- Option(
        jcache.get(PresentationCompilerKey.Default(language))
      )
    } {
      compiler.await.restart()
    }
  }

  /**
   * Restart the PC for this target and all build targets that depend on it.
   *
   * This is necessary when the user makes code changes and downstream targets may
   * have loaded symbols from that target (either from sources or its classpath). In order to see
   * these changes the PC needs to be restarted.
   */
  def restartPresentationCompilers(target: BuildTargetIdentifier): Unit = {
    // Restart PC for all build targets that depend on this target
    for {
      target <- buildTargets.allInverseDependencies(target)
      compiler <- buildTargetPCFromCache(target)
    } {
      scribe.debug(s"Restarting PC for target ${target.getUri}")
      compiler.restart()
    }
  }

  def completionItemResolve(
      item: CompletionItem
  ): Future[CompletionItem] = {
    for {
      data <- item.data
      compiler <- buildTargetPCFromCache(new BuildTargetIdentifier(data.target))
    } yield compiler.completionItemResolve(item, data.symbol).asScala
  }.getOrElse(Future.successful(item))

  /**
   * Calculates completions for a expression evaluator at breakpointPosition
   *
   * @param path path to file containing ht ebreakpoint
   * @param breakpointPosition actual breakpoint position
   * @param token cancel token for the compiler
   * @param expression expression that is currently being types
   * @param isZeroBased whether the client supports starting at 0 or 1 index
   * @return
   */
  def debugCompletions(
      path: AbsolutePath,
      breakpointPosition: LspPosition,
      token: CancelToken,
      expression: d.CompletionsArguments,
      isZeroBased: Boolean,
  ): Future[Seq[d.CompletionItem]] = {

    /**
     * Find the offset of the cursor inside the modified expression.
     * We need it to give the compiler the right offset.
     *
     * @param modified modified expression with indentation inserted
     * @param indentation indentation of the current breakpoint
     * @return offset where the compiler should insert completions
     */
    def expressionOffset(modified: String, indentation: String) = {
      val line = expression.getLine()
      val column = expression.getColumn()
      modified.split("\n").zipWithIndex.take(line).foldLeft(0) {
        case (offset, (lineText, index)) =>
          if (index + 1 == line) {
            if (line > 1)
              offset + column + indentation.size - 1
            else
              offset + column - 1
          } else offset + lineText.size + 1
      }
    }
    loadCompiler(path)
      .map { compiler =>
        val input = path.toInputFromBuffers(buffers)
        breakpointPosition.toMeta(input) match {
          case Some(metaPos) =>
            val oldText = metaPos.input.text
            val lineStart = oldText.indexWhere(
              c => c != ' ' && c != '\t',
              metaPos.start + 1,
            )

            val indentationSize = lineStart - metaPos.start
            val indentationChar =
              if (oldText.lift(lineStart - 1).exists(_ == '\t')) '\t' else ' '
            val indentation = indentationChar.toString * indentationSize

            val expressionText =
              expression.getText().replace("\n", s"\n$indentation")

            val prev = oldText.substring(0, lineStart)
            val succ = oldText.substring(lineStart)
            // insert expression at the start of breakpoint's line and move the lines one down
            val modified = s"$prev;$expressionText\n$indentation$succ"

            val rangeEnd =
              lineStart + expressionOffset(expressionText, indentation) + 1

            /**
             * Calculate the start if insertText is used for item, which does not declare an exact start.
             */
            def insertStart = {
              var i = rangeEnd - 1
              while (modified.charAt(i).isLetterOrDigit) i -= 1
              if (isZeroBased) i else i + 1
            }

            val offsetParams =
              CompilerOffsetParams(
                path.toURI,
                modified,
                rangeEnd,
                token,
                outlineFilesProvider.getOutlineFiles(compiler.buildTargetId()),
              )

            val previousLines = expression
              .getText()
              .split("\n")

            // we need to adjust start to point at the start of the replacement in the expression
            val adjustStart =
              /* For multiple line we need to insert at the correct offset and
               * then adjust column by indentation that was added to the expression
               */
              if (previousLines.size > 1)
                previousLines
                  .take(expression.getLine() - 1)
                  .map(_.size + 1)
                  .sum - indentationSize
              // for one line we only need to adjust column with indentation + ; that was added to the expression
              else -(1 + indentationSize)

            compiler
              .complete(offsetParams)
              .asScala
              .map(list =>
                list.getItems.asScala.toSeq
                  .map(
                    toDebugCompletionItem(
                      _,
                      adjustStart,
                      Position.Range(
                        input.copy(value = modified),
                        insertStart,
                        rangeEnd,
                      ),
                    )
                  )
              )
          case None =>
            scribe.debug(s"$breakpointPosition was not found in $path ")
            Future.successful(Nil)
        }
      }
      .getOrElse(Future.successful(Nil))
  }

  def semanticTokens(
      params: SemanticTokensParams,
      token: CancelToken,
  ): Future[SemanticTokens] = {
    val emptyTokens = Collections.emptyList[Integer]();
    if (!userConfig().enableSemanticHighlighting) {
      Future { new SemanticTokens(emptyTokens) }
    } else {
      val path = params.getTextDocument.getUri.toAbsolutePath
      loadCompiler(path)
        .map { compiler =>
          val (input, _, adjust) =
            sourceAdjustments(
              params.getTextDocument().getUri(),
              compiler.scalaVersion(),
            )

          val vFile =
            CompilerVirtualFileParams(
              path.toNIO.toUri(),
              input.text,
              token,
              outlineFilesProvider.getOutlineFiles(compiler.buildTargetId()),
            )
          val isScala3 = ScalaVersions.isScala3Version(compiler.scalaVersion())

          compiler
            .semanticTokens(vFile)
            .asScala
            .map { nodes =>
              val plist =
                try {
                  SemanticTokensProvider.provide(
                    nodes.asScala.toList,
                    vFile,
                    path,
                    isScala3,
                    trees,
                  )
                } catch {
                  case NonFatal(e) =>
                    scribe.error(
                      s"Failed to tokenize input for semantic tokens for $path",
                      e,
                    )
                    Nil
                }

              val tokens =
                SemanticTokensProvider.findCorrectStart(
                  0,
                  0,
                  plist.toList,
                  adjust,
                )
              if (isScala3 && path.isWorksheet) {
                new SemanticTokens(
                  SemanticTokensProvider.adjustForScala3Worksheet(tokens).asJava
                )
              } else {
                new SemanticTokens(tokens.asJava)
              }
            }
        }
        .getOrElse(Future.successful(new SemanticTokens(emptyTokens)))
    }

  }

  def inlayHints(
      params: InlayHintParams,
      token: CancelToken,
  ): Future[ju.List[InlayHint]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      def inlayHintsFallback(
          params: SyntheticDecorationsParams
      ): Future[ju.List[InlayHint]] = {
        pc.syntheticDecorations(params)
          .asScala
          .map(
            _.map { d =>
              val hint = new InlayHint()
              hint.setPosition(d.range().getStart())
              hint.setLabel(d.label())
              val kind =
                if (d.kind() <= 2) InlayHintKind.Type
                else InlayHintKind.Parameter
              hint.setKind(kind)
              hint.setData(
                internal.pc.InlayHints
                  .toData(params.uri().toString(), List(Left("")))
              )
              hint
            }
          )
      }

      def adjustInlayHints(
          inlayHints: ju.List[InlayHint]
      ): ju.List[InlayHint] = {
        inlayHints.asScala
          .dropWhile { hint =>
            val adjusted =
              adjust.adjustPos(hint.getPosition(), adjustToZero = false)
            adjusted.getLine() < 0 || adjusted.getCharacter() < 0
          }
          .map { hint =>
            hint.setPosition(adjust.adjustPos(hint.getPosition()))
            InlayHintCompat.maybeFixInlayHintData(
              hint,
              params.getTextDocument().getUri(),
            )
          }
          .asJava
      }

      val rangeParams =
        CompilerRangeParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      val options = userConfig().inlayHintsOptions
      val pcParams = CompilerInlayHintsParams(
        rangeParams,
        inferredTypes = options.inferredType,
        implicitParameters = options.implicitArguments,
        implicitConversions = options.implicitConversions,
        typeParameters = options.typeParameters,
        hintsInPatternMatch = options.hintsInPatternMatch,
      )

      pc
        .inlayHints(pcParams)
        .asScala
        .flatMap { hints =>
          if (hints.isEmpty) {
            inlayHintsFallback(pcParams.toSyntheticDecorationsParams)
              .map(adjustInlayHints)
          } else {
            Future.successful(adjustInlayHints(hints))
          }
        }
    }
      .getOrElse(Future.successful(Nil.asJava))
  }

  def completions(
      params: CompletionParams,
      token: CancelToken,
  ): Future[CompletionList] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val outlineFiles =
        outlineFilesProvider.getOutlineFiles(pc.buildTargetId())
      val offsetParams =
        CompilerOffsetParamsUtils.fromPos(pos, token, outlineFiles)
      pc.complete(offsetParams)
        .asScala
        .map { list =>
          adjust.adjustCompletionListInPlace(list)
          list
        }
    }.getOrElse(Future.successful(new CompletionList(Nil.asJava)))

  def autoImports(
      params: TextDocumentPositionParams,
      name: String,
      findExtensionMethods: Boolean,
      token: CancelToken,
  ): Future[ju.List[AutoImportsResult]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.autoImports(
        name,
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        ),
        findExtensionMethods,
      ).asScala
        .map { list =>
          list.map(adjust.adjustImportResult)
          list
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def insertInferredType(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val offset =
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      val result =
        if (
          pc.supportedCodeActions()
            .contains(CodeActionId.InsertInferredType)
        )
          pc.codeAction(
            offset,
            CodeActionId.InsertInferredType,
            ju.Optional.empty(),
          )
        else
          pc.insertInferredType(offset)

      result.asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def inlineEdits(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val offsetParams = CompilerOffsetParamsUtils.fromPos(
        pos,
        token,
        outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
      )
      val result =
        if (
          pc.supportedCodeActions()
            .contains(CodeActionId.InlineValue)
        )
          pc.codeAction(
            offsetParams,
            CodeActionId.InlineValue,
            ju.Optional.empty(),
          )
        else
          pc.inlineValue(offsetParams)

      result.asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }.getOrElse(Future.successful(Nil.asJava))

  def documentHighlight(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.List[DocumentHighlight]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.documentHighlight(
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      ).asScala
        .map { highlights =>
          adjust.adjustDocumentHighlight(highlights)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def references(
      params: ReferenceParams,
      token: CancelToken,
      additionalAdjust: AdjustRange,
  ): Future[List[ReferencesResult]] = {
    withPCAndAdjustLsp(params) { case (pc, pos, adjust) =>
      val requestParams = new internal.pc.PcReferencesRequest(
        CompilerOffsetParamsUtils.fromPos(pos, token),
        params.getContext().isIncludeDeclaration(),
        JEither.forLeft(pos.start),
      )
      pc.references(requestParams)
        .asScala
        .map(
          _.asScala
            .map(
              adjust.adjustReferencesResult(
                _,
                additionalAdjust,
                requestParams.file.text(),
              )
            )
            .toList
        )
    }
  }.getOrElse(Future.successful(Nil))

  def references(
      id: BuildTargetIdentifier,
      searchFiles: List[AbsolutePath],
      includeDefinition: Boolean,
      symbols: List[String],
      additionalAdjust: AdjustRange,
      isCancelled: () => Boolean,
  ): Future[List[ReferencesResult]] = {
    // we filter only Scala files, since `references` for Java are not implemented
    val filteredFiles = searchFiles.filter(_.isScala)
    val results =
      if (symbols.isEmpty || filteredFiles.isEmpty) Nil
      else
        withUncachedCompiler(id) { compiler =>
          for {
            searchFile <- filteredFiles
            if !isCancelled()
          } yield {
            val uri = searchFile.toURI
            val (input, _, adjust) =
              sourceAdjustments(uri.toString(), compiler.scalaVersion())
            val requestParams = new internal.pc.PcReferencesRequest(
              CompilerVirtualFileParams(uri, input.text),
              includeDefinition,
              JEither.forRight(symbols.head),
              symbols.tail.asJava,
            )
            compiler
              .references(requestParams)
              .asScala
              .map(
                _.asScala
                  .map(
                    adjust
                      .adjustReferencesResult(_, additionalAdjust, input.text)
                  )
                  .toList
              )
          }
        }
          .getOrElse(Nil)

    Future
      .sequence(results)
      .map(_.flatten)
      .map { res =>
        scribe.debug(
          s"PC references found: ${res.size}: ${res.mkString("\n")}"
        )
        res
      }
  }

  def extractMethod(
      doc: TextDocumentIdentifier,
      range: LspRange,
      extractionPos: LspPosition,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(doc.getUri(), range, extractionPos) {
      (pc, metaRange, metaExtractionPos, adjust) =>
        val rangeParams = CompilerRangeParamsUtils.fromPos(
          metaRange,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
        val extractionOffsetParams =
          CompilerOffsetParamsUtils.fromPos(metaExtractionPos, token)
        val result =
          if (
            pc.supportedCodeActions()
              .contains(CodeActionId.ImplementAbstractMembers)
          )
            pc.codeAction(
              rangeParams,
              CodeActionId.ExtractMethod,
              ju.Optional.of(extractionOffsetParams),
            )
          else
            pc.extractMethod(
              rangeParams,
              extractionOffsetParams,
            )

        result.asScala
          .map { edits =>
            adjust.adjustTextEdits(edits)
          }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def convertToNamedArguments(
      position: TextDocumentPositionParams,
      argIndices: ju.List[Integer],
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(position) { (pc, pos, adjust) =>
      val offset =
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      val result =
        if (
          pc.supportedCodeActions()
            .contains(CodeActionId.ConvertToNamedArguments)
        )
          pc.codeAction(
            offset,
            CodeActionId.ConvertToNamedArguments,
            ju.Optional.of(argIndices),
          )
        else
          pc.convertToNamedArguments(offset, argIndices)

      result.asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def implementAbstractMembers(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val offsetParams = CompilerOffsetParamsUtils.fromPos(
        pos,
        token,
        outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
      )

      val result =
        if (
          pc.supportedCodeActions()
            .contains(CodeActionId.ImplementAbstractMembers)
        )
          pc.codeAction(
            offsetParams,
            CodeActionId.ImplementAbstractMembers,
            None.asJava,
          )
        else
          pc.implementAbstractMembers(offsetParams)

      result.asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def codeAction(
      params: TextDocumentPositionParams,
      token: CancelToken,
      codeActionId: String,
      codeActionPayload: Option[Object],
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.codeAction(
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        ),
        codeActionId,
        codeActionPayload.asJava,
      ).asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def supportedCodeActions(path: AbsolutePath): ju.List[String] = {
    loadCompiler(path).map { pc =>
      pc.supportedCodeActions()
    }
  }.getOrElse(Nil.asJava)

  def hover(
      params: HoverExtParams,
      token: CancelToken,
  ): Future[Option[HoverSignature]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.hover(
        CompilerRangeParamsUtils.offsetOrRange(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      ).asScala
        .map(_.asScala.map { hover => adjust.adjustHoverResp(hover) })
    }
  }.getOrElse(Future.successful(None))

  def prepareRename(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.Optional[LspRange]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.prepareRename(
        CompilerRangeParamsUtils.offsetOrRange(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      ).asScala
        .map { range =>
          range.map(adjust.adjustRange(_))
        }
    }
  }.getOrElse(Future.successful(None.asJava))

  def rename(
      params: RenameParams,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.rename(
        CompilerRangeParamsUtils.offsetOrRange(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        ),
        params.getNewName(),
      ).asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def definition(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[DefinitionResult] = {
    definition(params = params, token = token, findTypeDef = false)
  }

  def typeDefinition(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[DefinitionResult] = {
    definition(params = params, token = token, findTypeDef = true)
  }

  def info(
      path: AbsolutePath,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    loadCompiler(path, forceScala = true)
      .map(
        _.info(symbol).asScala
          .map(_.asScala.map(PcSymbolInformation.from))
      )
      .getOrElse(Future(None))
  }

  def info(
      id: BuildTargetIdentifier,
      symbol: String,
  ): Future[Option[PcSymbolInformation]] = {
    loadCompiler(id)
      .map(
        _.info(symbol).asScala
          .map(_.asScala.map(PcSymbolInformation.from))
      )
      .getOrElse(Future(None))
  }

  private def definition(
      params: TextDocumentPositionParams,
      token: CancelToken,
      findTypeDef: Boolean,
  ): Future[DefinitionResult] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val params = CompilerOffsetParamsUtils.fromPos(
        pos,
        token,
        outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
      )
      val defResult =
        if (findTypeDef) pc.typeDefinition(params)
        else
          pc.definition(CompilerOffsetParamsUtils.fromPos(pos, token))

      for {
        c <- defResult.asScala
        locations <-
          if (c.isResolved())
            Future.successful(
              adjust.adjustLocations(c.locations()).asScala.toSeq
            )
          else
            locateInsideDecompiledJar(c.symbol(), c.locations().asScala.toSeq)
      } yield {
        val definitionPaths = locations.map { loc =>
          loc.getUri().toAbsolutePath
        }.toSet

        val definitionPath = if (definitionPaths.size == 1) {
          Some(definitionPaths.head)
        } else {
          None
        }
        DefinitionResult(
          locations.asJava,
          c.symbol(),
          definitionPath,
          None,
          c.symbol(),
        )
      }
    }.getOrElse(Future.successful(DefinitionResult.empty))

  def locateInsideDecompiledJar(
      symbol: String,
      locations: Seq[l.Location],
  ): Future[Seq[l.Location]] = {
    if (locations.isEmpty || !locations.head.getUri().endsWith(".class"))
      return Future.successful(Nil)

    scribe.debug(s"locateInsideDecompiledJar: $symbol, $locations")
    val decoder = DecompileBytecode.cfr
    val uri = locations.head.getUri()
    val pathClass = uri.stripSuffix(".class").toAbsolutePath
    for {
      decompiledCode <- decoder.decompilePath(
        pathClass,
        buildTargets.allWorkspaceJars.toList,
      )
    } yield {
      decompiledCode match {
        case Left(error) =>
          scribe.error(s"Error decompiling $pathClass: $error")
          Nil
        case Right(code) =>
          scribe.debug(s"Decompiled code length: ${code.length}")
          val index = mtags().index(
            Input.VirtualFile(s"$pathClass.java", code),
            m.dialects.Scala213,
          )
          val occurrences = index.occurrences.filter(sym =>
            sym.role == s.SymbolOccurrence.Role.DEFINITION && sym.symbol == symbol
          )
          if (occurrences.isEmpty) {
            scribe.warn(
              s"No occurrences found for symbol $symbol in decompiled $pathClass"
            )
            // return unchanged locations so we can at least open the file at the wrong position
            locations
          } else
            occurrences.map { occ =>
              new l.Location(uri.toString, occ.range.get.toLsp)
            }.toSeq
      }
    }

  }

  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[SignatureHelp] =
    withPCAndAdjustLsp(params) { (pc, pos, _) =>
      pc.signatureHelp(
        CompilerOffsetParamsUtils.fromPos(
          pos,
          token,
          outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
        )
      ).asScala
    }.getOrElse(Future.successful(new SignatureHelp()))

  def selectionRange(
      params: SelectionRangeParams,
      token: CancelToken,
  ): Future[ju.List[SelectionRange]] = {
    withPCAndAdjustLsp(params) { (pc, positions) =>
      val offsetPositions: ju.List[OffsetParams] =
        positions.map(
          CompilerOffsetParamsUtils.fromPos(
            _,
            token,
            outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
          )
        )
      pc.selectionRange(offsetPositions).asScala
    }.getOrElse(Future.successful(Nil.asJava))
  }

  def getTasty(
      buildTargetId: BuildTargetIdentifier,
      path: AbsolutePath,
  ): Option[Future[String]] = {
    loadCompiler(buildTargetId).map(
      _.getTasty(
        path.toURI,
        config.isHttpEnabled(),
      ).asScala
    )
  }

  /**
   * Gets presentation compiler for a file.
   * @param path for which presentation compiler should be loaded,
   *             resolves build target based on this file
   * @param forceScala if should use Scala pc for `.java` files that are in a Scala build target,
   *                   useful when Scala pc can handle Java files and Java pc implementation of a feature is missing
   */
  def loadCompiler(
      path: AbsolutePath,
      forceScala: Boolean = false,
  ): Option[PresentationCompiler] = {

    def fromBuildTarget: Option[PresentationCompiler] = {
      val target = buildTargets
        .inverseSources(path)

      target match {
        case None =>
          val tmpDirectory = workspace.resolve(Directories.tmp)
          val scalaVersion =
            scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
          if (
            !path.toNIO.startsWith(tmpDirectory.toNIO)
            // don't spam the log with the same message about the same file
            && !lastPathWithFallbackCompiler.contains(path)
          ) {
            scribe.debug(
              s"no build target found for $path, try syncing the file for full IDE support." +
                s" Using presentation compiler with project's scala-library version: ${scalaVersion}"
            )
            lastPathWithFallbackCompiler = Some(path)
          }
          Some(fallbackCompiler(path))
        case Some(value) =>
          if (path.isScalaFilename) loadCompiler(value)
          else if (path.isJavaFilename && forceScala)
            loadCompiler(value)
              .orElse(loadJavaCompiler(value))
          else if (path.isJavaFilename) loadJavaCompiler(value)
          else None
      }
    }

    if (!path.isScalaFilename && !path.isJavaFilename) None
    else if (path.isWorksheet)
      loadWorksheetCompiler(path).orElse(fromBuildTarget)
    else fromBuildTarget
  }

  def loadWorksheetCompiler(
      path: AbsolutePath
  ): Option[PresentationCompiler] = {
    worksheetProvider.getWorksheetPCData(path).flatMap { data =>
      maybeRestartWorksheetPresentationCompiler(path, data)
      worksheetsCache.get(path).map(_.await)
    }
  }

  private def maybeRestartWorksheetPresentationCompiler(
      path: AbsolutePath,
      data: WorksheetPcData,
  ): Unit = {
    val previousDigest = worksheetsDigests.getOrElse(path, "")
    if (data.digest != previousDigest) {
      worksheetsDigests.put(path, data.digest)
      restartWorksheetPresentationCompiler(
        path,
        data.classpath,
        data.dependencies,
      )
    }
  }

  private def restartWorksheetPresentationCompiler(
      path: AbsolutePath,
      classpath: List[Path],
      sources: List[Path],
  ): Unit = {
    val created: Option[Unit] = for {
      targetId <- buildTargets.inverseSources(path)
      scalaTarget <- buildTargets.scalaTarget(targetId)
      scalaVersion = scalaTarget.scalaVersion
      mtags <- {
        val result = mtagsResolver.resolve(scalaVersion)
        if (result.isEmpty) {
          scribe.warn(s"unsupported Scala ${scalaVersion}")
        }
        result
      }
    } yield {
      jworksheetsCache.put(
        path,
        workDoneProgress.trackBlocking(
          s"${config.icons().sync}Loading worksheet presentation compiler"
        ) {
          ScalaLazyCompiler.forWorksheet(
            scalaTarget,
            mtags,
            classpath,
            sources,
            search,
            completionItemPriority(),
          )
        },
      )
    }

    created.getOrElse {
      jworksheetsCache.put(
        path, {
          val scalaVersion =
            scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
          StandaloneCompiler(
            scalaVersion,
            classpath,
            sources,
            Some(search),
            completionItemPriority(),
          )
        },
      )
    }
  }

  private def loadJavaCompiler(
      targetId: BuildTargetIdentifier
  ): Option[PresentationCompiler] = {
    buildTargets.javaTarget(targetId).map { javaTarget =>
      jcache
        .computeIfAbsent(
          PresentationCompilerKey.JavaBuildTarget(targetId),
          { _ =>
            workDoneProgress.trackBlocking(
              s"${config.icons().sync}Loading presentation compiler"
            ) {
              JavaLazyCompiler(javaTarget, search, completionItemPriority())
            }
          },
        )
        .await
    }
  }

  private def loadCompiler(
      targetId: BuildTargetIdentifier
  ): Option[PresentationCompiler] =
    withKeyAndDefault(targetId) { case (key, getCompiler) =>
      def loadInitialFiles(pc: PresentationCompiler): PresentationCompiler = {
        val sources = buildTargets.buildTargetSources(targetId)
        val modifiedFiles =
          buffers.open.filter(buf => sources.exists(buf.startWith)).toList

        modifiedFiles.foreach(path =>
          pc.didChange(
            CompilerVirtualFileParams(path.toNIO.toUri, buffers.get(path).get)
          )
        )
        pc
      }

      var newCompiler = false

      // in case of a restart, the presentation compiler should know all files that have been modified
      // and use their up to date contents when type checking
      val pc = jcache
        .computeIfAbsent(key, { _ => newCompiler = true; getCompiler() })
        .await
      Option(if (newCompiler) loadInitialFiles(pc) else pc)
    }

  private def withKeyAndDefault[T](
      targetId: BuildTargetIdentifier
  )(
      f: (PresentationCompilerKey, () => MtagsPresentationCompiler) => Option[T]
  ): Option[T] = {
    buildTargets.scalaTarget(targetId).flatMap { scalaTarget =>
      val scalaVersion = scalaTarget.scalaVersion
      mtagsResolver.resolve(scalaVersion) match {
        case Some(mtags) =>
          def default() =
            workDoneProgress.trackBlocking(
              s"${config.icons().sync}Loading presentation compiler"
            ) {
              ScalaLazyCompiler(
                scalaTarget,
                mtags,
                search,
                completionItemPriority(),
              )
            }
          val key =
            PresentationCompilerKey.ScalaBuildTarget(scalaTarget.info.getId)
          f(key, default)
        case None =>
          scribe.warn(s"unsupported Scala ${scalaTarget.scalaVersion}")
          None
      }
    }
  }

  private def withUncachedCompiler[T](
      targetId: BuildTargetIdentifier
  )(f: PresentationCompiler => T): Option[T] =
    withKeyAndDefault(targetId) { case (key, getCompiler) =>
      val (out, shouldShutdown) = Option(jcache.get(key))
        .map((_, false))
        .getOrElse((getCompiler(), true))
      if (shouldShutdown)
        scribe.debug(s"starting uncached presentation compiler for $targetId")
      val compiler = Option(out.await)
      val result = compiler.map(f)
      if (shouldShutdown) compiler.foreach(_.shutdown())
      result
    }

  private def withPCAndAdjustLsp[T](
      params: SelectionRangeParams
  )(
      fn: (PresentationCompiler, ju.List[Position]) => T
  ): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path).map { compiler =>
      val input = path
        .toInputFromBuffers(buffers)
        .copy(path = params.getTextDocument.getUri)

      val positions =
        params.getPositions().asScala.flatMap(_.toMeta(input)).asJava

      fn(compiler, positions)
    }
  }

  private def withPCAndAdjustLsp[T](
      params: TextDocumentPositionParams
  )(
      fn: (PresentationCompiler, Position, AdjustLspData) => T
  ): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path).flatMap { compiler =>
      val (input, pos, adjust) =
        sourceAdjustments(
          params,
          compiler.scalaVersion(),
        )
      pos
        .toMeta(input)
        .map(metaPos => fn(compiler, metaPos, adjust))
    }
  }

  private def withPCAndAdjustLsp[T](
      params: InlayHintParams
  )(
      fn: (PresentationCompiler, Position, AdjustLspData) => T
  ): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path).flatMap { case compiler =>
      val (input, pos, adjust) =
        sourceAdjustments(
          params,
          compiler.scalaVersion(),
        )
      pos
        .toMeta(input)
        .map(metaPos => fn(compiler, metaPos, adjust))
    }
  }

  private def withPCAndAdjustLsp[T](
      uri: String,
      range: LspRange,
      extractionPos: LspPosition,
  )(
      fn: (
          PresentationCompiler,
          Position,
          Position,
          AdjustLspData,
      ) => T
  ): Option[T] = {
    val path = uri.toAbsolutePath
    loadCompiler(path).flatMap { compiler =>
      val (input, adjustRequest, adjustResponse) =
        sourceAdjustments(
          uri,
          compiler.scalaVersion(),
        )
      for {
        metaRange <- new LspRange(
          adjustRequest(range.getStart()),
          adjustRequest(range.getEnd()),
        ).toMeta(input)
        metaExtractionPos <- adjustRequest(extractionPos).toMeta(input)
      } yield fn(
        compiler,
        metaRange,
        metaExtractionPos,
        adjustResponse,
      )
    }
  }

  private def withPCAndAdjustLsp[T](
      params: HoverExtParams
  )(
      fn: (
          PresentationCompiler,
          Position,
          AdjustLspData,
      ) => T
  ): Option[T] = {

    val path = params.textDocument.getUri.toAbsolutePath
    loadCompiler(path).flatMap { compiler =>
      if (params.range != null) {
        val (input, range, adjust) = sourceAdjustments(
          params,
          compiler.scalaVersion(),
        )
        range.toMeta(input).map(fn(compiler, _, adjust))

      } else {
        val positionParams =
          new TextDocumentPositionParams(
            params.textDocument,
            params.getPosition,
          )
        val (input, pos, adjust) = sourceAdjustments(
          positionParams,
          compiler.scalaVersion(),
        )
        pos.toMeta(input).map(fn(compiler, _, adjust))
      }
    }
  }

  private def sourceAdjustments(
      params: TextDocumentPositionParams,
      scalaVersion: String,
  ): (Input.VirtualFile, LspPosition, AdjustLspData) = {
    val (input, adjustRequest, adjustResponse) = sourceAdjustments(
      params.getTextDocument().getUri(),
      scalaVersion,
    )
    (input, adjustRequest(params.getPosition()), adjustResponse)
  }

  private def sourceAdjustments(
      params: InlayHintParams,
      scalaVersion: String,
  ): (Input.VirtualFile, LspRange, AdjustLspData) = {
    val (input, adjustRequest, adjustResponse) = sourceAdjustments(
      params.getTextDocument.getUri(),
      scalaVersion,
    )
    val start = params.getRange.getStart()
    val end = params.getRange.getEnd()
    val newRange = new LspRange(adjustRequest(start), adjustRequest(end))
    (input, newRange, adjustResponse)
  }

  private def sourceAdjustments(
      params: HoverExtParams,
      scalaVersion: String,
  ): (Input.VirtualFile, LspRange, AdjustLspData) = {
    val (input, adjustRequest, adjustResponse) = sourceAdjustments(
      params.textDocument.getUri(),
      scalaVersion,
    )
    val start = params.range.getStart()
    val end = params.range.getEnd()
    val newRange = new LspRange(adjustRequest(start), adjustRequest(end))
    (input, newRange, adjustResponse)
  }

  private def sourceAdjustments(
      uri: String,
      scalaVersion: String,
  ): (Input.VirtualFile, LspPosition => LspPosition, AdjustLspData) = {
    val path = uri.toAbsolutePath
    sourceMapper.pcMapping(path, scalaVersion)
  }

  private def toDebugCompletionType(
      kind: CompletionItemKind
  ): d.CompletionItemType = {
    kind match {
      case CompletionItemKind.Constant => d.CompletionItemType.VALUE
      case CompletionItemKind.Value => d.CompletionItemType.VALUE
      case CompletionItemKind.Keyword => d.CompletionItemType.KEYWORD
      case CompletionItemKind.Class => d.CompletionItemType.CLASS
      case CompletionItemKind.TypeParameter => d.CompletionItemType.CLASS
      case CompletionItemKind.Operator => d.CompletionItemType.FUNCTION
      case CompletionItemKind.Field => d.CompletionItemType.FIELD
      case CompletionItemKind.Method => d.CompletionItemType.METHOD
      case CompletionItemKind.Unit => d.CompletionItemType.UNIT
      case CompletionItemKind.Enum => d.CompletionItemType.ENUM
      case CompletionItemKind.Interface => d.CompletionItemType.INTERFACE
      case CompletionItemKind.Constructor => d.CompletionItemType.CONSTRUCTOR
      case CompletionItemKind.Folder => d.CompletionItemType.FILE
      case CompletionItemKind.Module => d.CompletionItemType.MODULE
      case CompletionItemKind.EnumMember => d.CompletionItemType.ENUM
      case CompletionItemKind.Snippet => d.CompletionItemType.SNIPPET
      case CompletionItemKind.Function => d.CompletionItemType.FUNCTION
      case CompletionItemKind.Color => d.CompletionItemType.COLOR
      case CompletionItemKind.Text => d.CompletionItemType.TEXT
      case CompletionItemKind.Property => d.CompletionItemType.PROPERTY
      case CompletionItemKind.Reference => d.CompletionItemType.REFERENCE
      case CompletionItemKind.Variable => d.CompletionItemType.VARIABLE
      case CompletionItemKind.Struct => d.CompletionItemType.MODULE
      case CompletionItemKind.File => d.CompletionItemType.FILE
      case _ => d.CompletionItemType.TEXT
    }
  }

  private def toDebugCompletionItem(
      item: CompletionItem,
      adjustStart: Int,
      insertTextPosition: Position.Range,
  ): d.CompletionItem = {
    val debugItem = new d.CompletionItem()
    debugItem.setLabel(item.getLabel())
    val (newText, range) = Option(item.getTextEdit()).map(_.asScala) match {
      case Some(Left(textEdit)) =>
        (textEdit.getNewText, textEdit.getRange)
      case Some(Right(insertReplace)) =>
        (insertReplace.getNewText, insertReplace.getReplace)
      case None =>
        Option(item.getInsertText()).orElse(Option(item.getLabel())) match {
          case Some(text) =>
            (text, insertTextPosition.toLsp)
          case None =>
            throw new RuntimeException(
              "Completion item does not contain expected data"
            )
        }
    }
    val start = range.getStart().getCharacter + adjustStart

    val length = range.getEnd().getCharacter() - range.getStart().getCharacter()
    debugItem.setLength(length)

    // remove snippets, since they are not supported in DAP
    val fullText = newText.replaceAll("\\$[1-9]+", "")

    val selection = fullText.indexOf("$0")

    // Find the spot for the cursor
    if (selection >= 0) {
      debugItem.setSelectionStart(selection)
    }

    debugItem.setDetail(item.getDetail())
    debugItem.setText(fullText.replace("$0", ""))
    debugItem.setStart(start)
    debugItem.setType(toDebugCompletionType(item.getKind()))
    debugItem.setSortText(item.getFilterText())
    debugItem
  }

  def batchSemanticdbTextDocuments(
      sources: Seq[AbsolutePath],
      cancelToken: CancelToken,
  ): Future[s.TextDocuments] = {
    val futures = for {
      (pc, paths) <- sources
        .flatMap(s => loadCompiler(s).map(pc => (pc, s)))
        .groupBy(_._1)
    } yield {
      val params = paths.map { case (_, s) =>
        CompilerVirtualFileParams(
          s.toURI,
          s.toInputFromBuffers(buffers).text,
          token = cancelToken,
        ): VirtualFileParams
      }
      pc.batchSemanticdbTextDocuments(params.asJava).asScala.map { bytes =>
        s.TextDocuments.parseFrom(bytes).documents
      }
    }
    Future
      .sequence(futures)
      .map(docss => s.TextDocuments(documents = docss.flatten.toSeq))
  }

  def semanticdbTextDocument(
      source: AbsolutePath,
      text: String,
  ): s.TextDocument = {
    val pc = loadCompiler(source).getOrElse(fallbackCompiler(source))

    val (prependedLinesSize, modifiedText) =
      Option
        .when(source.isSbt)(
          buildTargets
            .sbtAutoImports(source)
        )
        .flatten
        .fold((0, text))(imports =>
          (imports.size, SbtBuildTool.prependAutoImports(text, imports))
        )

    // NOTE(olafur): it's unfortunate that we block on `semanticdbTextDocument`
    // here but to avoid it we would need to refactor the `Semanticdbs` trait,
    // which requires more effort than it's worth.
    val params = new CompilerVirtualFileParams(
      source.toURI,
      modifiedText,
      token = EmptyCancelToken,
      outlineFiles = outlineFilesProvider.getOutlineFiles(pc.buildTargetId()),
    )
    val bytes = pc
      .semanticdbTextDocument(params)
      .get(
        config.initialConfig.compilers.timeoutDelay,
        config.initialConfig.compilers.timeoutUnit,
      )
    val textDocument = {
      val doc = s.TextDocument.parseFrom(bytes)
      if (doc.text.isEmpty()) doc.withText(text)
      else doc
    }
    if (prependedLinesSize > 0)
      cleanupAutoImports(textDocument, text, prependedLinesSize)
    else textDocument
  }

  private def cleanupAutoImports(
      document: s.TextDocument,
      originalText: String,
      linesSize: Int,
  ): s.TextDocument = {

    def adjustRange(range: s.Range): Option[s.Range] = {
      val nextStartLine = range.startLine - linesSize
      val nextEndLine = range.endLine - linesSize
      if (nextEndLine >= 0) {
        val nextRange = range.copy(
          startLine = nextStartLine,
          endLine = nextEndLine,
        )
        Some(nextRange)
      } else None
    }

    val adjustedOccurences =
      document.occurrences.flatMap { occurence =>
        occurence.range
          .flatMap(adjustRange)
          .map(r => occurence.copy(range = Some(r)))
      }

    val adjustedDiagnostic =
      document.diagnostics.flatMap { diagnostic =>
        diagnostic.range
          .flatMap(adjustRange)
          .map(r => diagnostic.copy(range = Some(r)))
      }

    val adjustedSynthetic =
      document.synthetics.flatMap { synthetic =>
        synthetic.range
          .flatMap(adjustRange)
          .map(r => synthetic.copy(range = Some(r)))
      }

    s.TextDocument(
      schema = document.schema,
      uri = document.uri,
      text = originalText,
      md5 = MD5.compute(originalText),
      language = document.language,
      symbols = document.symbols,
      occurrences = adjustedOccurences,
      diagnostics = adjustedDiagnostic,
      synthetics = adjustedSynthetic,
    )
  }

}

object Compilers {

  sealed trait PresentationCompilerKey
  object PresentationCompilerKey {
    final case class ScalaBuildTarget(id: BuildTargetIdentifier)
        extends PresentationCompilerKey
    final case class JavaBuildTarget(id: BuildTargetIdentifier)
        extends PresentationCompilerKey
    final case class Default(language: s.Language)
        extends PresentationCompilerKey
  }

  // To be removed in the future after:
  // - https://github.com/scalameta/metals/pull/7430
  // - https://github.com/scala/scala3/pull/22259
  case class DidChangeCompilerFileParams(
      uri: URI,
      text: String,
      override val shouldReturnDiagnostics: Boolean,
      token: CancelToken = EmptyCancelToken,
  ) extends VirtualFileParams

}
