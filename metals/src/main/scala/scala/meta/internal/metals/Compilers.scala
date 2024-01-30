package scala.meta.internal.metals

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}

import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.decorations.DecorationOptions
import scala.meta.internal.metals.CompilerOffsetParamsUtils
import scala.meta.internal.metals.CompilerRangeParamsUtils
import scala.meta.internal.metals.Compilers.PresentationCompilerKey
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.JavaPresentationCompiler
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.telemetry
import scala.meta.internal.telemetry.ReporterContext
import scala.meta.internal.telemetry.ScalaPresentationCompilerContext
import scala.meta.internal.telemetry.TelemetryReportContext
import scala.meta.internal.worksheets.WorksheetPcData
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.ReportContext
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.{Position => LspPosition}
import org.eclipse.lsp4j.{Range => LspRange}
import org.eclipse.lsp4j.{debug => d}

/**
 * Manages lifecycle for presentation compilers in all build targets.
 *
 * We need a custom presentation compiler for each build target since
 * build targets can have different classpaths and compiler settings.
 */
class Compilers(
    workspace: AbsolutePath,
    config: ClientConfiguration,
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
    buffers: Buffers,
    search: SymbolSearch,
    embedded: Embedded,
    statusBar: StatusBar,
    sh: ScheduledExecutorService,
    initializeParams: InitializeParams,
    excludedPackages: () => ExcludedPackagesHandler,
    scalaVersionSelector: ScalaVersionSelector,
    trees: Trees,
    mtagsResolver: MtagsResolver,
    sourceMapper: SourceMapper,
    worksheetProvider: WorksheetProvider,
)(implicit ec: ExecutionContextExecutorService, rc: ReportContext)
    extends Cancelable {
  val plugins = new CompilerPlugins()

  // Not a TrieMap because we want to avoid loading duplicate compilers for the same build target.
  // Not a `j.u.c.ConcurrentHashMap` because it can deadlock in `computeIfAbsent` when the absent
  // function is expensive, which is the case here.
  val jcache: ju.Map[PresentationCompilerKey, PresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[PresentationCompilerKey, PresentationCompiler]
    )
  private val jworksheetsCache: ju.Map[AbsolutePath, PresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[AbsolutePath, PresentationCompiler]
    )

  private val worksheetsDigests = new TrieMap[AbsolutePath, String]()

  private val cache = jcache.asScala
  private def buildTargetPCFromCache(
      id: BuildTargetIdentifier
  ): Option[PresentationCompiler] =
    cache.get(PresentationCompilerKey.BuildTarget(id)).filter(_ != null)

  private val worksheetsCache = jworksheetsCache.asScala

  private def createStandaloneCompiler(
      scalaVersion: String,
      classpath: Seq[Path],
      standaloneSearch: SymbolSearch,
      name: String,
      path: AbsolutePath,
  ): PresentationCompiler = {
    val mtags =
      mtagsResolver.resolve(scalaVersion).getOrElse(MtagsBinaries.BuildIn)

    val tmpDirectory = workspace.resolve(Directories.tmp)
    if (!path.toNIO.startsWith(tmpDirectory.toNIO))
      scribe.info(
        s"no build target found for $path. Using presentation compiler with project's scala-library version: ${mtags.scalaVersion}"
      )
    newCompiler(
      mtags,
      List.empty,
      classpath ++ Embedded.scalaLibrary(scalaVersion),
      standaloneSearch,
      name,
    )
  }

  // The "fallback" compiler is used for source files that don't belong to a build target.
  def fallbackCompiler(path: AbsolutePath): PresentationCompiler = {
    jcache.compute(
      PresentationCompilerKey.Default,
      (_, value) => {
        val scalaVersion =
          scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
        val existingPc = Option(value).flatMap { pc =>
          if (pc.scalaVersion == scalaVersion) {
            Some(pc)
          } else {
            pc.shutdown()
            None
          }
        }
        existingPc match {
          case Some(pc) => pc
          case None =>
            createStandaloneCompiler(
              scalaVersion,
              List.empty,
              Try(
                StandaloneSymbolSearch(
                  scalaVersion,
                  workspace,
                  buffers,
                  excludedPackages,
                  userConfig,
                  trees,
                  buildTargets,
                  saveSymbolFileToDisk = !config.isVirtualDocumentSupported(),
                  sourceMapper,
                )
              ).getOrElse(EmptySymbolSearch),
              "default",
              path,
            )
        }
      },
    )
  }

  def loadedPresentationCompilerCount(): Int = cache.values.count(_.isLoaded())

  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    Cancelable.cancelEach(worksheetsCache.values)(_.shutdown())
    cache.clear()
    worksheetsCache.clear()
    worksheetsDigests.clear()
  }

  def restartAll(): Unit = {
    val count = cache.size
    cancel()
    scribe.info(
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
            pc.hover(
              CompilerOffsetParams(
                Paths.get("Main.scala").toUri(),
                "object Ma\n",
                "object Ma".length(),
              )
            ).thenApply(_.map(_.toLsp()))
          }
        }
      }
    }

  def didClose(path: AbsolutePath): Unit = {
    loadCompiler(path).foreach { pc =>
      pc.didClose(path.toNIO.toUri())
    }
  }

  def didChange(path: AbsolutePath): Future[List[Diagnostic]] = {
    def originInput =
      path
        .toInputFromBuffers(buffers)

    loadCompiler(path)
      .map { pc =>
        val inputAndAdjust =
          if (
            path.isWorksheet && ScalaVersions.isScala3Version(pc.scalaVersion())
          ) {
            WorksheetProvider.worksheetScala3AdjustmentsForPC(originInput)
          } else {
            None
          }

        val (input, adjust) = inputAndAdjust.getOrElse(
          originInput,
          AdjustedLspData.default,
        )

        for {
          ds <-
            pc
              .didChange(
                CompilerVirtualFileParams(path.toNIO.toUri(), input.value)
              )
              .asScala
        } yield {
          ds.asScala.map(adjust.adjustDiagnostic).toList
        }
      }
      .getOrElse(Future.successful(Nil))
  }

  def didCompile(report: CompileReport): Unit = {
    if (report.getErrors > 0) {
      buildTargetPCFromCache(report.getTarget).foreach(_.restart())
    } else {
      // Restart PC for all build targets that depend on this target since the classfiles
      // may have changed.
      for {
        target <- buildTargets.allInverseDependencies(report.getTarget)
        compiler <- buildTargetPCFromCache(target)
      } {
        compiler.restart()
      }
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

  def log: List[String] =
    if (config.initialConfig.compilers.debug) {
      List(
        "-Ypresentation-debug",
        "-Ypresentation-verbose",
        "-Ypresentation-log",
        workspace.resolve(Directories.pc).toString(),
      )
    } else {
      Nil
    }

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

            val offsetParams = CompilerOffsetParams(
              path.toURI,
              modified,
              rangeEnd,
              token,
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

          /**
           * Find the start that is actually contained in the file and not
           * in the added parts such as imports in sbt.
           *
           * @param line line within the adjusted source
           * @param character line within the adjusted source
           * @param remaining the rest of the tokens to analyze
           * @return the first found that should be contained with the rest
           */
          @tailrec
          def findCorrectStart(
              line: Integer,
              character: Integer,
              remaining: List[Integer],
          ): List[Integer] = {
            remaining match {
              case lineDelta :: charDelta :: next =>
                val newCharacter: Integer =
                  // only increase character delta if the same line
                  if (lineDelta == 0) character + charDelta
                  else charDelta

                val adjustedTokenPos = adjust.adjustPos(
                  new LspPosition(line + lineDelta, newCharacter),
                  adjustToZero = false,
                )
                if (
                  adjustedTokenPos.getLine() >= 0 &&
                  adjustedTokenPos.getCharacter() >= 0
                )
                  (adjustedTokenPos.getLine(): Integer) ::
                    (adjustedTokenPos.getCharacter(): Integer) :: next
                else
                  findCorrectStart(
                    line + lineDelta,
                    newCharacter,
                    next.drop(3),
                  )
              case _ => Nil
            }
          }

          def adjustForScala3Worksheet(tokens: List[Integer]): List[Integer] = {
            @tailrec
            @nowarn
            def loop(
                remaining: List[Integer],
                acc: List[List[Integer]],
                adjustColumnDelta: Int =
                  0, // after multiline string we need to adjust column delta of the next token in line
            ): List[Integer] = {
              remaining match {
                case Nil => acc.reverse.flatten
                // we need to remove additional indent
                case deltaLine :: deltaColumn :: len :: next
                    if deltaLine != 0 =>
                  if (deltaColumn - 2 >= 0) {
                    val adjustedColumn: Integer = deltaColumn - 2
                    val adjusted: List[Integer] =
                      List(deltaLine, adjustedColumn, len) ++ next.take(2)
                    loop(
                      next.drop(2),
                      adjusted :: acc,
                    )
                  }
                  // for multiline strings, we highlight the entire line inluding leading whitespace
                  // so we need to adjust the length after removing additional indent
                  else {
                    val deltaLen = deltaColumn - 2
                    val adjustedLen: Integer = Math.max(0, len + deltaLen)
                    val adjusted: List[Integer] =
                      List(deltaLine, deltaColumn, adjustedLen) ++ next.take(2)
                    loop(
                      next.drop(2),
                      adjusted :: acc,
                      deltaLen,
                    )
                  }
                case deltaLine :: deltaColumn :: next =>
                  val adjustedColumn: Integer = deltaColumn + adjustColumnDelta
                  val adjusted: List[Integer] =
                    List(deltaLine, adjustedColumn) ++ next.take(3)
                  loop(
                    next.drop(3),
                    adjusted :: acc,
                  )
              }
            }

            // Delta for first token was already adjusted in `findCorrectStart`
            loop(tokens.drop(5), List(tokens.take(5)))
          }

          val vFile =
            CompilerVirtualFileParams(path.toNIO.toUri(), input.text, token)
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
                    isScala3,
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
                findCorrectStart(0, 0, plist.toList)
              if (isScala3 && path.isWorksheet) {
                new SemanticTokens(adjustForScala3Worksheet(tokens).asJava)
              } else {
                new SemanticTokens(tokens.asJava)
              }
            }
        }
        .getOrElse(Future.successful(new SemanticTokens(emptyTokens)))
    }

  }

  def syntheticDecorations(
      path: AbsolutePath,
      token: CancelToken,
  ): Future[ju.List[DecorationOptions]] = {
    loadCompiler(path)
      .map { compiler =>
        val (input, _, adjust) =
          sourceAdjustments(
            path.toNIO.toUri().toString(),
            compiler.scalaVersion(),
          )

        def adjustDecorations(
            decorations: ju.List[SyntheticDecoration]
        ): ju.List[DecorationOptions] = {
          val withCorrectStart = decorations.asScala.dropWhile { d =>
            val adjusted = adjust
              .adjustPos(d.range().getStart(), adjustToZero = false)
            adjusted.getLine() < 0 || adjusted.getCharacter() < 0
          }
          withCorrectStart.map { decoration =>
            DecorationOptions(
              decoration.label(),
              adjust.adjustRange(decoration.range()),
            )
          }.asJava
        }
        val vFile =
          CompilerVirtualFileParams(path.toNIO.toUri(), input.text, token)

        val pcParams = CompilerSyntheticDecorationsParams(
          vFile,
          typeParameters = userConfig().showInferredType.contains("true"),
          inferredTypes = userConfig().showInferredType.contains("minimal") ||
            userConfig().showInferredType.contains("true"),
          implicitParameters = userConfig().showImplicitArguments,
          implicitConversions = userConfig().showImplicitConversionsAndClasses,
        )
        compiler
          .syntheticDecorations(pcParams)
          .asScala
          .map { decorations =>
            adjustDecorations(decorations)
          }

      }
      .getOrElse(Future.successful(Nil.asJava))

  }

  def completions(
      params: CompletionParams,
      token: CancelToken,
  ): Future[CompletionList] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val offsetParams =
        CompilerOffsetParamsUtils.fromPos(pos, token)
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
        CompilerOffsetParamsUtils.fromPos(pos, token),
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
      pc.insertInferredType(CompilerOffsetParamsUtils.fromPos(pos, token))
        .asScala
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
      pc.inlineValue(CompilerOffsetParamsUtils.fromPos(pos, token))
        .asScala
        .map(adjust.adjustTextEdits)
    }.getOrElse(Future.successful(Nil.asJava))

  def documentHighlight(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.List[DocumentHighlight]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.documentHighlight(CompilerOffsetParamsUtils.fromPos(pos, token))
        .asScala
        .map { highlights =>
          adjust.adjustDocumentHighlight(highlights)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def extractMethod(
      doc: TextDocumentIdentifier,
      range: LspRange,
      extractionPos: LspPosition,
      token: CancelToken,
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(doc.getUri(), range, extractionPos) {
      (pc, metaRange, metaExtractionPos, adjust) =>
        pc.extractMethod(
          CompilerRangeParamsUtils.fromPos(metaRange, token),
          CompilerOffsetParamsUtils.fromPos(metaExtractionPos, token),
        ).asScala
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
      pc.convertToNamedArguments(
        CompilerOffsetParamsUtils.fromPos(pos, token),
        argIndices,
      ).asScala
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
      pc.implementAbstractMembers(CompilerOffsetParamsUtils.fromPos(pos, token))
        .asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }.getOrElse(Future.successful(Nil.asJava))

  def hover(
      params: HoverExtParams,
      token: CancelToken,
  ): Future[Option[HoverSignature]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.hover(CompilerRangeParamsUtils.offsetOrRange(pos, token))
        .asScala
        .map(_.asScala.map { hover => adjust.adjustHoverResp(hover) })
    }
  }.getOrElse(Future.successful(None))

  def prepareRename(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[ju.Optional[LspRange]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.prepareRename(
        CompilerRangeParamsUtils.offsetOrRange(pos, token)
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
        CompilerRangeParamsUtils.offsetOrRange(pos, token),
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

  private def definition(
      params: TextDocumentPositionParams,
      token: CancelToken,
      findTypeDef: Boolean,
  ): Future[DefinitionResult] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val params = CompilerOffsetParamsUtils.fromPos(pos, token)
      val defResult =
        if (findTypeDef) pc.typeDefinition(params)
        else
          pc.definition(CompilerOffsetParamsUtils.fromPos(pos, token))
      defResult.asScala
        .map { c =>
          adjust.adjustLocations(c.locations())
          val definitionPaths = c
            .locations()
            .map { loc =>
              loc.getUri().toAbsolutePath
            }
            .asScala
            .toSet

          val definitionPath = if (definitionPaths.size == 1) {
            Some(definitionPaths.head)
          } else {
            None
          }
          DefinitionResult(
            c.locations(),
            c.symbol(),
            definitionPath,
            None,
          )
        }
    }.getOrElse(Future.successful(DefinitionResult.empty))

  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken,
  ): Future[SignatureHelp] =
    withPCAndAdjustLsp(params) { (pc, pos, _) =>
      pc.signatureHelp(CompilerOffsetParamsUtils.fromPos(pos, token)).asScala
    }.getOrElse(Future.successful(new SignatureHelp()))

  def selectionRange(
      params: SelectionRangeParams,
      token: CancelToken,
  ): Future[ju.List[SelectionRange]] = {
    withPCAndAdjustLsp(params) { (pc, positions) =>
      val offsetPositions: ju.List[OffsetParams] =
        positions.map(CompilerOffsetParamsUtils.fromPos(_, token))
      pc.selectionRange(offsetPositions).asScala
    }.getOrElse(Future.successful(Nil.asJava))
  }

  def loadCompiler(
      path: AbsolutePath
  ): Option[PresentationCompiler] = {

    def fromBuildTarget: Option[PresentationCompiler] = {
      val target = buildTargets
        .inverseSources(path)

      target match {
        case None => Some(fallbackCompiler(path))
        case Some(value) =>
          if (path.isScalaFilename) loadCompiler(value)
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
      worksheetsCache.get(path)
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
        statusBar.trackBlockingTask(
          s"${config.icons.sync}Loading worksheet presentation compiler"
        ) {
          val worksheetSearch = new StandaloneSymbolSearch(
            workspace,
            classpath.map(AbsolutePath(_)),
            sources.map(AbsolutePath(_)),
            buffers,
            excludedPackages,
            trees,
            buildTargets,
            saveSymbolFileToDisk = !config.isVirtualDocumentSupported(),
            sourceMapper,
            workspaceFallback = Some(search),
          )
          newCompiler(
            scalaTarget,
            mtags,
            classpath,
            worksheetSearch,
          )
        },
      )
    }

    created.getOrElse {
      jworksheetsCache.put(
        path, {
          val scalaVersion =
            scalaVersionSelector.fallbackScalaVersion(isAmmonite = false)
          createStandaloneCompiler(
            scalaVersion,
            classpath,
            StandaloneSymbolSearch(
              scalaVersion,
              workspace,
              buffers,
              sources,
              classpath,
              excludedPackages,
              userConfig,
              trees,
              buildTargets,
              saveSymbolFileToDisk = !config.isVirtualDocumentSupported(),
              sourceMapper,
            ),
            path.toString(),
            path,
          )
        },
      )
    }
  }

  def loadCompiler(
      targetId: BuildTargetIdentifier
  ): Option[PresentationCompiler] = {
    val target = buildTargets.scalaTarget(targetId)
    target.flatMap(loadCompilerForTarget)
  }

  def loadJavaCompiler(
      targetId: BuildTargetIdentifier
  ): Option[PresentationCompiler] = {
    val targetClasspath = buildTargets.targetClasspath(targetId)
    targetClasspath.flatMap(classpath =>
      loadJavaCompilerForTarget(targetId.getUri, classpath)
    )
  }

  def loadCompilerForTarget(
      scalaTarget: ScalaTarget
  ): Option[PresentationCompiler] = {
    val scalaVersion = scalaTarget.scalaVersion
    mtagsResolver.resolve(scalaVersion) match {
      case Some(mtags) =>
        val out = jcache.computeIfAbsent(
          PresentationCompilerKey.BuildTarget(scalaTarget.info.getId),
          { _ =>
            statusBar.trackBlockingTask(
              s"${config.icons.sync}Loading presentation compiler"
            ) {
              newCompiler(scalaTarget, mtags, search)
            }
          },
        )
        Option(out)
      case None =>
        scribe.warn(s"unsupported Scala ${scalaTarget.scalaVersion}")
        None
    }
  }

  def loadJavaCompilerForTarget(
      targetUri: String,
      classpath: List[String],
  ): Option[PresentationCompiler] = {
    val pc = JavaPresentationCompiler()
    Some(
      configure(pc, search, log)
        .newInstance(
          targetUri,
          classpath.toAbsoluteClasspath.map(_.toNIO).toSeq.asJava,
          log.asJava,
        )
    )
  }

  private def withPCAndAdjustLsp[T](
      params: SelectionRangeParams
  )(fn: (PresentationCompiler, ju.List[Position]) => T): Option[T] = {
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
  )(fn: (PresentationCompiler, Position, AdjustLspData) => T): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path).flatMap { compiler =>
      val (input, pos, adjust) =
        sourceAdjustments(
          params,
          compiler.scalaVersion(),
        )
      pos.toMeta(input).map(metaPos => fn(compiler, metaPos, adjust))
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
      } yield fn(compiler, metaRange, metaExtractionPos, adjustResponse)
    }
  }

  private def withPCAndAdjustLsp[T](
      params: HoverExtParams
  )(fn: (PresentationCompiler, Position, AdjustLspData) => T): Option[T] = {

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

  private def createTelemetryReporterContext(
      scalaVersion: String,
      config: PresentationCompilerConfig,
      options: List[String],
  ): ReporterContext =
    new ScalaPresentationCompilerContext(
      scalaVersion,
      options.asJava,
      telemetry.conversion.PresentationCompilerConfig(config),
    )

  private def remoteReporting(
      createTelemetryReporterContext: () => ReporterContext
  ): ReportContext = {
    val logger =
      ju.logging.Logger.getLogger(classOf[TelemetryReportContext].getName)
    new TelemetryReportContext(
      telemetryLevel = () => userConfig().telemetryLevel,
      reporterContext = createTelemetryReporterContext,
      sanitizers = new TelemetryReportContext.Sanitizers(
        workspace = Some(workspace.toNIO),
        sourceCodeTransformer = Some(ScalametaSourceCodeTransformer),
      ),
      logger = LoggerAccess(
        debug = logger.fine(_),
        info = logger.info(_),
        warning = logger.warning(_),
        error = logger.severe(_),
      ),
    )
  }

  private def getUserConfiguration(): PresentationCompilerConfig = {
    val options =
      InitializationOptions.from(initializeParams).compilerOptions
    config.initialConfig.compilers
      .update(options)
      .copy(
        _symbolPrefixes = userConfig().symbolPrefixes,
        isCompletionSnippetsEnabled =
          initializeParams.supportsCompletionSnippets,
        _isStripMarginOnTypeFormattingEnabled =
          () => userConfig().enableStripMarginOnTypeFormatting,
      )
  }

  private def configure(
      pc: PresentationCompiler,
      search: SymbolSearch,
      options: List[String],
  ): PresentationCompiler = {
    val config = getUserConfiguration()
    val remoteReportContext = remoteReporting(() =>
      createTelemetryReporterContext(pc.scalaVersion, config, options)
    )

    pc.withSearch(search)
      .withExecutorService(ec)
      .withWorkspace(workspace.toNIO)
      .withScheduledExecutorService(sh)
      .withReportsLoggerLevel(MetalsServerConfig.default.loglevel)
      .withConfiguration(config)
      .withAdditionalReportContexts(List(remoteReportContext).asJava)
  }

  def newCompiler(
      target: ScalaTarget,
      mtags: MtagsBinaries,
      search: SymbolSearch,
  ): PresentationCompiler = {
    val classpath =
      target.scalac.classpath.toAbsoluteClasspath.map(_.toNIO).toSeq
    newCompiler(target, mtags, classpath, search)
  }

  def newCompiler(
      target: ScalaTarget,
      mtags: MtagsBinaries,
      classpath: Seq[Path],
      search: SymbolSearch,
  ): PresentationCompiler = {
    newCompiler(
      mtags,
      target.scalac.getOptions().asScala.toSeq,
      classpath,
      search,
      target.scalac.getTarget.getUri,
    ).withBuildTargetName(target.displayName)
  }

  def newCompiler(
      mtags: MtagsBinaries,
      options: Seq[String],
      classpath: Seq[Path],
      search: SymbolSearch,
      name: String,
  ): PresentationCompiler = {
    val pc: PresentationCompiler =
      mtags match {
        case MtagsBinaries.BuildIn => new ScalaPresentationCompiler()
        case artifacts: MtagsBinaries.Artifacts =>
          embedded.presentationCompiler(artifacts, classpath)
      }

    val filteredOptions = plugins.filterSupportedOptions(options)
    val allOptions = log ++ filteredOptions
    configure(pc, search, allOptions)
      .newInstance(
        name,
        classpath.asJava,
        (allOptions).asJava,
      )
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

}

object Compilers {

  sealed trait PresentationCompilerKey
  object PresentationCompilerKey {
    final case class BuildTarget(id: BuildTargetIdentifier)
        extends PresentationCompilerKey
    case object Default extends PresentationCompilerKey
  }
}
