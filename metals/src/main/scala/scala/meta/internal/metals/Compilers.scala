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
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.mtags
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.{Position => LspPosition}

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
    diagnostics: Diagnostics,
    isExcludedPackage: String => Boolean
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {
  val plugins = new CompilerPlugins()

  // Not a TrieMap because we want to avoid loading duplicate compilers for the same build target.
  // Not a `j.u.c.ConcurrentHashMap` because it can deadlock in `computeIfAbsent` when the absent
  // function is expensive, which is the case here.
  val jcache: ju.Map[BuildTargetIdentifier, PresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[BuildTargetIdentifier, PresentationCompiler]
    )
  private val jworksheetsCache: ju.Map[AbsolutePath, PresentationCompiler] =
    Collections.synchronizedMap(
      new java.util.HashMap[AbsolutePath, PresentationCompiler]
    )

  private val cache = jcache.asScala

  private val worksheetsCache = jworksheetsCache.asScala

  // The "rambo" compiler is used for source files that don't belong to a build target.
  lazy val ramboCompiler: PresentationCompiler = createStandaloneCompiler(
    PackageIndex.scalaLibrary,
    Try(StandaloneSymbolSearch(workspace, buffers, isExcludedPackage))
      .getOrElse(EmptySymbolSearch),
    "metals-default"
  )

  private def createStandaloneCompiler(
      classpath: Seq[Path],
      standaloneSearch: SymbolSearch,
      name: String
  ): PresentationCompiler = {
    scribe.info(
      "no build target: using presentation compiler with only scala-library"
    )
    val compiler =
      configure(new ScalaPresentationCompiler(), standaloneSearch)
        .newInstance(
          s"$name-${mtags.BuildInfo.scalaCompilerVersion}",
          classpath.asJava,
          Nil.asJava
        )
    ramboCancelable = Cancelable(() => compiler.shutdown())
    compiler
  }

  var ramboCancelable = Cancelable.empty

  def loadedPresentationCompilerCount(): Int = cache.values.count(_.isLoaded())

  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    cache.clear()
    ramboCancelable.cancel()
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

  def foldingRange(
      params: FoldingRangeRequestParams,
      token: CancelToken
  ): Future[ju.List[FoldingRange]] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    val input = path.toInputFromBuffers(buffers)
    pc.foldingRange(
      CompilerVirtualFileParams(path.toNIO.toUri, input.value)
    ).asScala
  }

  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): Future[ju.List[TextEdit]] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    val input = path.toInputFromBuffers(buffers)
    pc.onTypeFormatting(params, input.value).asScala
  }

  def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): Future[ju.List[TextEdit]] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    val input = path.toInputFromBuffers(buffers)
    pc.rangeFormatting(params, input.value).asScala
  }

  def documentSymbol(
      params: DocumentSymbolParams
  ): Future[ju.List[DocumentSymbol]] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    val input = path.toInputFromBuffers(buffers)
    pc.documentSymbols(
      CompilerVirtualFileParams(path.toNIO.toUri, input.value)
    ).asScala
  }

  def didClose(path: AbsolutePath): Unit = {
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    pc.didClose(path.toNIO.toUri())
  }

  def didChange(path: AbsolutePath): Future[Unit] = {

    def originInput =
      path
        .toInputFromBuffers(buffers)

    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
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
      ds.asScala.headOption match {
        case None =>
          diagnostics.onNoSyntaxError(path)
        case Some(diagnostic) =>
          diagnostics.onSyntaxError(path, adjust.adjustDiagnostic(diagnostic))
      }
    }
  }

  def didCompile(report: CompileReport): Unit = {
    if (report.getErrors > 0) {
      cache.get(report.getTarget).foreach(_.restart())
    } else {
      // Restart PC for all build targets that depend on this target since the classfiles
      // may have changed.
      for {
        target <- buildTargets.allInverseDependencies(report.getTarget)
        compiler <- cache.get(target)
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
      compiler <- cache.get(new BuildTargetIdentifier(data.target))
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

  def completions(
      params: CompletionParams,
      token: CancelToken
  ): Future[CompletionList] =
    withPCAndAdjustLsp(params, None) { (pc, pos, adjust) =>
      pc.complete(CompilerOffsetParams.fromPos(pos, token))
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
    withPCAndAdjustLsp(params, None) { (pc, pos, adjust) =>
      pc.autoImports(name, CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { list =>
          list.map(adjust.adjustImportResult)
          list
        }
    }
  }

  def implementAbstractMembers(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[ju.List[TextEdit]] = {
    withPCAndAdjustLsp(params, None) { (pc, pos, adjust) =>
      pc.implementAbstractMembers(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { edits =>
          adjust.adjustTextEdits(edits)
        }
    }
  }

  def hover(
      params: TextDocumentPositionParams,
      token: CancelToken,
      interactiveSemanticdbs: InteractiveSemanticdbs
  ): Future[Option[Hover]] =
    withPCAndAdjustLsp(params, Some(interactiveSemanticdbs)) {
      (pc, pos, adjust) =>
        pc.hover(CompilerOffsetParams.fromPos(pos, token))
          .asScala
          .map(_.asScala.map { hover => adjust.adjustHoverResp(hover) })
    }

  def definition(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[DefinitionResult] =
    withPCAndAdjustLsp(params, None) { (pc, pos, adjust) =>
      pc.definition(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { c =>
          adjust.adjustLocations(c.locations())
          DefinitionResult(
            c.locations(),
            c.symbol(),
            None,
            None
          )
        }
    }

  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken,
      interactiveSemanticdbs: InteractiveSemanticdbs
  ): Future[SignatureHelp] =
    withPCAndAdjustLsp(params, Some(interactiveSemanticdbs)) {
      (pc, pos, adjust) =>
        pc.signatureHelp(CompilerOffsetParams.fromPos(pos, token)).asScala
    }

  def enclosingClass(
      pos: LspPosition,
      path: AbsolutePath,
      token: CancelToken = EmptyCancelToken
  ): Future[Option[String]] = {
    val input = path.toInputFromBuffers(buffers)
    val offset = pos.toMeta(input).start
    val params = CompilerOffsetParams(path.toURI, input.text, offset, token)
    loadCompiler(path, None) match {
      case Some(pc) =>
        pc.enclosingClass(params).asScala.map(_.asScala)
      case None => Future.successful(None)
    }
  }

  def loadCompiler(
      path: AbsolutePath,
      interactiveSemanticdbs: Option[InteractiveSemanticdbs]
  ): Option[PresentationCompiler] = {

    def fromBuildTarget: Option[PresentationCompiler] = {
      val target = buildTargets
        .inverseSources(path)
        .orElse(interactiveSemanticdbs.flatMap(_.getBuildTarget(path)))
      target match {
        case None =>
          if (path.isScalaFilename) Some(ramboCompiler)
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
      isSupported =
        ScalaVersions.isSupportedScalaVersion(scalaTarget.scalaVersion)
      _ = {
        if (!isSupported) {
          scribe.warn(s"unsupported Scala ${scalaTarget.scalaVersion}")
        }
      }
      if isSupported
      scalac <- buildTargets.scalacOptions(targetId)
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
            workspaceFallback = Some(search)
          )
          newCompiler(scalac, scalaTarget, classpath, worksheetSearch)
        }
      )
    }

    created.getOrElse {
      jworksheetsCache.put(
        path,
        createStandaloneCompiler(
          classpath,
          StandaloneSymbolSearch(
            workspace,
            buffers,
            sources,
            classpath,
            isExcludedPackage
          ),
          path.toString()
        )
      )
    }
  }

  def loadCompiler(
      target: BuildTargetIdentifier
  ): Option[PresentationCompiler] =
    buildTargets.scalaTarget(target).flatMap(loadCompilerForTarget)

  def loadCompilerForTarget(
      scalaTarget: ScalaTarget
  ): Option[PresentationCompiler] = {
    val isSupported =
      ScalaVersions.isSupportedScalaVersion(scalaTarget.scalaVersion)
    if (!isSupported) {
      scribe.warn(s"unsupported Scala ${scalaTarget.scalaVersion}")
      None
    } else {
      val out = jcache.computeIfAbsent(
        scalaTarget.info.getId,
        { _ =>
          statusBar.trackBlockingTask(
            s"${config.icons.sync}Loading presentation compiler"
          ) {
            newCompiler(scalaTarget.scalac, scalaTarget, search)
          }
        }
      )
      Some(out)
    }
  }

  private def ammoniteInputPosOpt(
      path: AbsolutePath,
      position: LspPosition,
      interactiveSemanticdbs: Option[InteractiveSemanticdbs]
  ): Option[(Input.VirtualFile, LspPosition)] =
    if (path.isAmmoniteScript)
      for {
        target <-
          buildTargets
            .inverseSources(path)
            .orElse(interactiveSemanticdbs.flatMap(_.getBuildTarget(path)))
        res <- ammonite().generatedScalaInputForPc(
          target,
          path,
          position
        )
      } yield res
    else
      None

  private def withPCAndAdjustLsp[T](
      params: TextDocumentPositionParams,
      interactiveSemanticdbs: Option[InteractiveSemanticdbs]
  )(fn: (PresentationCompiler, Position, AdjustLspData) => T): T = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val compiler =
      loadCompiler(path, interactiveSemanticdbs).getOrElse(ramboCompiler)

    val (input, pos, adjust) =
      sourceAdjustments(
        params,
        interactiveSemanticdbs,
        compiler.scalaVersion()
      )
    val metaPos = pos.toMeta(input)
    fn(compiler, metaPos, adjust)

  }

  private def sourceAdjustments(
      params: TextDocumentPositionParams,
      interactiveSemanticdbs: Option[InteractiveSemanticdbs],
      scalaVersion: String
  ): (Input.VirtualFile, LspPosition, AdjustLspData) = {

    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    val position = params.getPosition

    def input = path.toInputFromBuffers(buffers)
    def default = (input, position, AdjustedLspData.default)

    val forScripts =
      if (path.isAmmoniteScript) {
        ammoniteInputPosOpt(path, position, interactiveSemanticdbs)
          .map {
            case (input, pos) =>
              (input, pos, Ammonite.adjustLspData(input.text))
          }
      } else if (path.isSbt) {
        buildTargets
          .sbtAutoImports(path)
          .map(
            SbtBuildTool.sbtInputPosAdjustment(input, _, uri, position)
          )
      } else if (
        path.isWorksheet && ScalaVersions.isScala3Version(scalaVersion)
      ) {
        WorksheetProvider.worksheetScala3Adjustments(input, uri, position)
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
            isFoldOnlyLines = initializeParams.foldOnlyLines,
            _isStripMarginOnTypeFormattingEnabled =
              () => userConfig().enableStripMarginOnTypeFormatting
          )
      )

  def newCompiler(
      scalac: ScalacOptionsItem,
      target: ScalaTarget,
      search: SymbolSearch
  ): PresentationCompiler = {
    val classpath = scalac.classpath.map(_.toNIO).toSeq
    newCompiler(scalac, target, classpath, search)
  }

  def newCompiler(
      scalac: ScalacOptionsItem,
      target: ScalaTarget,
      classpath: Seq[Path],
      search: SymbolSearch
  ): PresentationCompiler = {
    // The metals_2.12 artifact depends on mtags_2.12.x where "x" matches
    // `mtags.BuildInfo.scalaCompilerVersion`. In the case when
    // `info.getScalaVersion == mtags.BuildInfo.scalaCompilerVersion` then we
    // skip fetching the mtags module from Maven.
    val pc: PresentationCompiler =
      if (
        ScalaVersions.isCurrentScalaCompilerVersion(
          target.scalaInfo.getScalaVersion()
        )
      ) {
        new ScalaPresentationCompiler()
      } else {
        embedded.presentationCompiler(target.scalaInfo, scalac)
      }
    val options = plugins.filterSupportedOptions(scalac.getOptions.asScala)
    configure(pc, search).newInstance(
      scalac.getTarget.getUri,
      classpath.asJava,
      (log ++ options).asJava
    )
  }
}
