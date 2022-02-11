package scala.meta.internal.metals

import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.Compilers.PresentationCompilerKey
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.parsing.Trees
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SignatureHelp
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
    ammonite: () => Ammonite,
    buildTargets: BuildTargets,
    buffers: Buffers,
    search: SymbolSearch,
    embedded: Embedded,
    statusBar: StatusBar,
    sh: ScheduledExecutorService,
    initializeParams: Option[InitializeParams],
    isExcludedPackage: String => Boolean,
    scalaVersionSelector: ScalaVersionSelector,
    trees: Trees,
    mtagsResolver: MtagsResolver
)(implicit ec: ExecutionContextExecutorService)
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

  private val cache = jcache.asScala
  private def buildTargetPCFromCache(
      id: BuildTargetIdentifier
  ): Option[PresentationCompiler] =
    cache.get(PresentationCompilerKey.BuildTarget(id))

  private val worksheetsCache = jworksheetsCache.asScala

  private def createStandaloneCompiler(
      scalaVersion: String,
      classpath: Seq[Path],
      standaloneSearch: SymbolSearch,
      name: String,
      path: AbsolutePath
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
      name
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
                  isExcludedPackage,
                  userConfig,
                  trees,
                  buildTargets,
                  saveSymbolFileToDisk = !config.isVirtualDocumentSupported()
                )
              ).getOrElse(EmptySymbolSearch),
              "default",
              path
            )
        }
      }
    )
  }

  def loadedPresentationCompilerCount(): Int = cache.values.count(_.isLoaded())

  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    Cancelable.cancelEach(worksheetsCache.values)(_.shutdown())
    cache.clear()
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
          .flatMap(path => buildTargets.inverseSources(path).toList)
          .distinct
        targets.foreach { target =>
          loadCompiler(target).foreach { pc =>
            pc.hover(
              CompilerOffsetParams(
                Paths.get("Main.scala").toUri(),
                "object Ma\n",
                "object Ma".length()
              )
            )
          }
        }
      }
    }

  def didClose(path: AbsolutePath): Unit = {
    val pc = loadCompiler(path).getOrElse(fallbackCompiler(path))
    pc.didClose(path.toNIO.toUri())
  }

  def didChange(path: AbsolutePath): Future[List[Diagnostic]] = {

    def originInput =
      path
        .toInputFromBuffers(buffers)

    val pc = loadCompiler(path).getOrElse(fallbackCompiler(path))
    val inputAndAdjust =
      if (
        path.isWorksheet && ScalaVersions.isScala3Version(pc.scalaVersion())
      ) {
        WorksheetProvider.worksheetScala3Adjustments(originInput, path)
      } else {
        None
      }

    val (input, adjust) = inputAndAdjust.getOrElse(
      originInput,
      AdjustedLspData.default
    )

    for {
      ds <-
        pc
          .didChange(CompilerVirtualFileParams(path.toNIO.toUri(), input.value))
          .asScala
    } yield {
      ds.asScala.map(adjust.adjustDiagnostic).toList
    }
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
      item: CompletionItem,
      token: CancelToken
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
        workspace.resolve(Directories.pc).toString()
      )
    } else {
      Nil
    }

  def debugCompletions(
      path: AbsolutePath,
      breakpointPosition: LspPosition,
      token: CancelToken,
      expression: d.CompletionsArguments
  ): Future[Seq[d.CompletionItem]] = {

    val compiler = loadCompiler(path).getOrElse(fallbackCompiler(path))

    val input = path.toInputFromBuffers(buffers)
    val metaPos = breakpointPosition.toMeta(input)
    val oldText = metaPos.input.text
    val lineStart = oldText.indexWhere(
      c => c != ' ' && c != '\t',
      metaPos.start + 1
    )

    val indentation = lineStart - metaPos.start
    // insert expression at the start of breakpoint's line and move the lines one down
    val modified =
      s"${oldText.substring(0, lineStart)}${expression
        .getText()}\n${" " * indentation}${oldText.substring(lineStart)}"

    val offsetParams = CompilerOffsetParams(
      path.toURI,
      modified,
      lineStart + expression.getColumn() - 1,
      token
    )
    compiler
      .complete(offsetParams)
      .asScala
      .map(list =>
        list.getItems.asScala
          .map(
            toDebugCompletionItem(
              _,
              indentation
            )
          )
      )
  }

  def completions(
      params: CompletionParams,
      token: CancelToken
  ): Future[CompletionList] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      val offsetParams =
        CompilerOffsetParams.fromPos(pos, token)
      pc.complete(offsetParams)
        .asScala
        .map { list =>
          adjust.adjustCompletionListInPlace(list)
          list
        }
    }

  def autoImports(
      params: TextDocumentPositionParams,
      name: String,
      token: CancelToken
  ): Future[ju.List[AutoImportsResult]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.autoImports(name, CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { list =>
          list.map(adjust.adjustImportResult)
          list
        }
    }
  }

  def insertInferredType(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.insertInferredType(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }

  def implementAbstractMembers(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.implementAbstractMembers(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }

  def hover(
      params: HoverExtParams,
      token: CancelToken
  ): Future[Option[Hover]] = {
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.hover(CompilerRangeParams.offsetOrRange(pos, token))
        .asScala
        .map(_.asScala.map { hover => adjust.adjustHoverResp(hover) })
    }
  }

  def definition(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[DefinitionResult] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.definition(CompilerOffsetParams.fromPos(pos, token))
        .asScala
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
            None
          )
        }
    }

  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[SignatureHelp] =
    withPCAndAdjustLsp(params) { (pc, pos, adjust) =>
      pc.signatureHelp(CompilerOffsetParams.fromPos(pos, token)).asScala
    }

  def selectionRange(
      params: SelectionRangeParams,
      token: CancelToken
  ): Future[ju.List[SelectionRange]] = {
    withPCAndAdjustLsp(params) { (pc, positions) =>
      val offsetPositions: ju.List[OffsetParams] =
        positions.map(CompilerOffsetParams.fromPos(_, token))
      pc.selectionRange(offsetPositions).asScala
    }
  }

  def loadCompiler(
      path: AbsolutePath
  ): Option[PresentationCompiler] = {

    def fromBuildTarget: Option[PresentationCompiler] = {
      val target = buildTargets
        .inverseSources(path)
      target match {
        case None =>
          if (path.isScalaFilename) Some(fallbackCompiler(path))
          else None
        case Some(value) => loadCompiler(value)
      }
    }

    if (path.isWorksheet) loadWorksheetCompiler(path).orElse(fromBuildTarget)
    else fromBuildTarget
  }

  def loadWorksheetCompiler(
      path: AbsolutePath
  ): Option[PresentationCompiler] = {
    worksheetsCache.get(path)
  }

  def restartWorksheetPresentationCompiler(
      path: AbsolutePath,
      classpath: List[Path],
      sources: List[Path]
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
            isExcludedPackage,
            trees,
            buildTargets,
            saveSymbolFileToDisk = !config.isVirtualDocumentSupported(),
            workspaceFallback = Some(search)
          )
          newCompiler(
            scalaTarget.scalac,
            scalaTarget,
            mtags,
            classpath,
            worksheetSearch
          )
        }
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
              isExcludedPackage,
              userConfig,
              trees,
              buildTargets,
              saveSymbolFileToDisk = !config.isVirtualDocumentSupported()
            ),
            path.toString(),
            path
          )
        }
      )
    }
  }

  def loadCompiler(
      targetId: BuildTargetIdentifier
  ): Option[PresentationCompiler] = {
    val target = buildTargets.scalaTarget(targetId)
    target.flatMap(loadCompilerForTarget)
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
              newCompiler(scalaTarget.scalac, scalaTarget, mtags, search)
            }
          }
        )
        Option(out)
      case None =>
        scribe.warn(s"unsupported Scala ${scalaTarget.scalaVersion}")
        None
    }
  }

  private def ammoniteInputPosOpt(
      path: AbsolutePath
  ): Option[(Input.VirtualFile, LspPosition => LspPosition)] =
    if (path.isAmmoniteScript)
      for {
        target <-
          buildTargets
            .inverseSources(path)
        res <- ammonite().generatedScalaInputForPc(
          target,
          path
        )
      } yield res
    else
      None

  private def withPCAndAdjustLsp[T](
      params: SelectionRangeParams
  )(fn: (PresentationCompiler, ju.List[Position]) => T): T = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val compiler =
      loadCompiler(path).getOrElse(fallbackCompiler(path))

    val input = path
      .toInputFromBuffers(buffers)
      .copy(path = params.getTextDocument.getUri)

    val positions = params.getPositions().map(_.toMeta(input))

    fn(compiler, positions)
  }

  private def withPCAndAdjustLsp[T](
      params: TextDocumentPositionParams
  )(fn: (PresentationCompiler, Position, AdjustLspData) => T): T = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val compiler = loadCompiler(path).getOrElse(fallbackCompiler(path))

    val (input, pos, adjust) =
      sourceAdjustments(
        params,
        compiler.scalaVersion()
      )
    val metaPos = pos.toMeta(input)
    fn(compiler, metaPos, adjust)
  }

  private def withPCAndAdjustLsp[T](
      params: HoverExtParams
  )(fn: (PresentationCompiler, Position, AdjustLspData) => T): T = {

    val path = params.textDocument.getUri.toAbsolutePath
    val compiler = loadCompiler(path).getOrElse(fallbackCompiler(path))

    if (params.range != null) {
      val (input, range, adjust) = sourceAdjustments(
        params,
        compiler.scalaVersion()
      )
      fn(compiler, range.toMeta(input), adjust)

    } else {
      val positionParams =
        new TextDocumentPositionParams(params.textDocument, params.getPosition)
      val (input, pos, adjust) = sourceAdjustments(
        positionParams,
        compiler.scalaVersion()
      )
      fn(compiler, pos.toMeta(input), adjust)
    }
  }

  private def sourceAdjustments(
      params: TextDocumentPositionParams,
      scalaVersion: String
  ): (Input.VirtualFile, LspPosition, AdjustLspData) = {
    val (input, adjustRequest, adjustResponse) = sourceAdjustments(
      params.getTextDocument().getUri(),
      scalaVersion
    )
    (input, adjustRequest(params.getPosition()), adjustResponse)
  }

  private def sourceAdjustments(
      params: HoverExtParams,
      scalaVersion: String
  ): (Input.VirtualFile, LspRange, AdjustLspData) = {
    val (input, adjustRequest, adjustResponse) = sourceAdjustments(
      params.textDocument.getUri(),
      scalaVersion
    )
    val start = params.range.getStart()
    val end = params.range.getEnd()
    val newRange = new LspRange(adjustRequest(start), adjustRequest(end))
    (input, newRange, adjustResponse)
  }

  private def sourceAdjustments(
      uri: String,
      scalaVersion: String
  ): (Input.VirtualFile, LspPosition => LspPosition, AdjustLspData) = {
    val path = uri.toAbsolutePath
    def input = path.toInputFromBuffers(buffers)
    def default =
      (input, (position: LspPosition) => position, AdjustedLspData.default)

    val forScripts =
      if (path.isAmmoniteScript) {
        ammoniteInputPosOpt(path)
          .map { case (input, pos) =>
            (input, pos, Ammonite.adjustLspData(input.text))
          }
      } else if (path.isSbt) {
        buildTargets
          .sbtAutoImports(path)
          .map(
            SbtBuildTool.sbtInputPosAdjustment(input, _)
          )
      } else if (
        path.isWorksheet && ScalaVersions.isScala3Version(scalaVersion)
      ) {
        WorksheetProvider.worksheetScala3Adjustments(input, uri)
      } else None

    forScripts.getOrElse(default)
  }

  private def configure(
      pc: PresentationCompiler,
      search: SymbolSearch
  ): PresentationCompiler =
    pc.withSearch(search)
      .withExecutorService(ec)
      .withWorkspace(workspace.toNIO)
      .withScheduledExecutorService(sh)
      .withConfiguration(
        initializeParams
          .map(params => {
            val options = InitializationOptions.from(params).compilerOptions
            config.initialConfig.compilers.update(options)
          })
          .getOrElse(config.initialConfig.compilers)
          .copy(
            _symbolPrefixes = userConfig().symbolPrefixes,
            isCompletionSnippetsEnabled =
              initializeParams.supportsCompletionSnippets,
            _isStripMarginOnTypeFormattingEnabled =
              () => userConfig().enableStripMarginOnTypeFormatting
          )
      )

  def newCompiler(
      scalac: ScalacOptionsItem,
      target: ScalaTarget,
      mtags: MtagsBinaries,
      search: SymbolSearch
  ): PresentationCompiler = {
    val classpath = scalac.classpath.toAbsoluteClasspath.map(_.toNIO).toSeq
    newCompiler(scalac, target, mtags, classpath, search)
  }

  def newCompiler(
      scalac: ScalacOptionsItem,
      target: ScalaTarget,
      mtags: MtagsBinaries,
      classpath: Seq[Path],
      search: SymbolSearch
  ): PresentationCompiler = {
    newCompiler(
      mtags,
      scalac.getOptions().asScala,
      classpath,
      search,
      scalac.getTarget.getUri
    )
  }

  def newCompiler(
      mtags: MtagsBinaries,
      options: Seq[String],
      classpath: Seq[Path],
      search: SymbolSearch,
      name: String
  ): PresentationCompiler = {
    val pc: PresentationCompiler =
      mtags match {
        case MtagsBinaries.BuildIn => new ScalaPresentationCompiler()
        case artifacts: MtagsBinaries.Artifacts =>
          embedded.presentationCompiler(artifacts, classpath)

      }

    val filteredOptions = plugins.filterSupportedOptions(options)
    configure(pc, search).newInstance(
      name,
      classpath.asJava,
      (log ++ filteredOptions).asJava
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
      indentation: Int
  ): d.CompletionItem = {
    val debugItem = new d.CompletionItem()
    debugItem.setLabel(item.getLabel())
    val (newText, range) = item.getTextEdit().asScala match {
      case Left(textEdit) =>
        (textEdit.getNewText, textEdit.getRange)
      case Right(insertReplace) =>
        (insertReplace.getNewText, insertReplace.getReplace)
    }
    val start = range.getStart().getCharacter() - indentation
    val end = range.getEnd().getCharacter() - indentation

    val length = end - start
    debugItem.setLength(length)

    // remove snippets, since they are not supported in DAP
    val fullText = newText.replaceAll("\\$[1-9]+", "")

    val selection = fullText.indexOf("$0")

    // Find the spot for the cursor
    if (selection >= 0) {
      debugItem.setSelectionStart(selection)
    }

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
