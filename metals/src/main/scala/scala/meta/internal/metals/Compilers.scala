package scala.meta.internal.metals

import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.ScheduledExecutorService
import java.{util => ju}

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.mtags
import scala.meta.internal.pc.EmptySymbolSearch
import scala.meta.internal.pc.LogMessages
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolSearch

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.ScalaBuildTarget
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
    config: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    ammonite: () => Ammonite,
    buildTargets: BuildTargets,
    buffers: Buffers,
    search: SymbolSearch,
    embedded: Embedded,
    statusBar: StatusBar,
    sh: ScheduledExecutorService,
    initializeParams: Option[InitializeParams],
    diagnostics: Diagnostics
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
  private val cache = jcache.asScala

  // The "rambo" compiler is used for source files that don't belong to a build target.
  lazy val ramboCompiler: PresentationCompiler = {
    scribe.info(
      "no build target: using presentation compiler with only scala-library"
    )
    val ramboSearch =
      Try(new RamboSymbolSearch(workspace, buffers))
        .getOrElse(EmptySymbolSearch)
    val compiler =
      configure(new ScalaPresentationCompiler(), ramboSearch).newInstance(
        s"metals-default-${mtags.BuildInfo.scalaCompilerVersion}",
        PackageIndex.scalaLibrary.asJava,
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
    val pc = loadCompiler(path, None).getOrElse(ramboCompiler)
    val input = path.toInputFromBuffers(buffers)
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
          diagnostics.onSyntaxError(path, diagnostic)
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
    if (config.compilers.debug) {
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
    withPC(params, None) { (pc, pos) =>
      pc.complete(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { list =>
          if (params.getTextDocument.getUri.isAmmoniteScript)
            Ammonite.adjustCompletionListInPlace(list, pos.input.text)
          list
        }
    }.getOrElse(Future.successful(new CompletionList()))

  def autoImports(
      params: TextDocumentPositionParams,
      name: String,
      token: CancelToken
  ): Future[ju.List[AutoImportsResult]] = {
    withPC(params, None) { (pc, pos) =>
      pc.autoImports(name, CompilerOffsetParams.fromPos(pos, token)).asScala
    }.getOrElse(Future.successful(new ju.ArrayList))
  }

  def implementAbstractMembers(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[ju.List[TextEdit]] = {
    withPC(params, None) { (pc, pos) =>
      pc.implementAbstractMembers(CompilerOffsetParams.fromPos(pos, token))
        .asScala
    }.getOrElse(Future.successful(new ju.ArrayList))
  }

  def hover(
      params: TextDocumentPositionParams,
      token: CancelToken,
      interactiveSemanticdbs: InteractiveSemanticdbs
  ): Future[Option[Hover]] =
    withPC(params, Some(interactiveSemanticdbs)) { (pc, pos) =>
      pc.hover(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map(_.asScala.map { hover =>
          if (params.getTextDocument.getUri.isAmmoniteScript)
            Ammonite.adjustHoverResp(hover, pos.input.text)
          else
            hover
        })
    }.getOrElse {
      Future.successful(Option.empty)
    }

  def definition(
      params: TextDocumentPositionParams,
      token: CancelToken
  ): Future[DefinitionResult] =
    withPC(params, None) { (pc, pos) =>
      pc.definition(CompilerOffsetParams.fromPos(pos, token))
        .asScala
        .map { c =>
          DefinitionResult(
            c.locations(),
            c.symbol(),
            None,
            None
          )
        }
    }.getOrElse(Future.successful(DefinitionResult.empty))

  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelToken,
      interactiveSemanticdbs: InteractiveSemanticdbs
  ): Future[SignatureHelp] =
    withPC(params, Some(interactiveSemanticdbs)) { (pc, pos) =>
      pc.signatureHelp(CompilerOffsetParams.fromPos(pos, token)).asScala
    }.getOrElse(Future.successful(new SignatureHelp()))

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

  def loadCompiler(
      target: BuildTargetIdentifier
  ): Option[PresentationCompiler] = {
    for {
      info <- buildTargets.scalaTarget(target)
      isSupported = ScalaVersions.isSupportedScalaVersion(info.scalaVersion)
      _ = {
        if (!isSupported) {
          scribe.warn(s"unsupported Scala ${info.scalaVersion}")
        }
      }
      if isSupported
      scalac <- buildTargets.scalacOptions(target)
    } yield {
      jcache.computeIfAbsent(
        target,
        { _ =>
          statusBar.trackBlockingTask(
            s"${config.icons.sync}Loading presentation compiler"
          ) {
            newCompiler(scalac, info.scalaInfo)
          }
        }
      )
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

  private def withPC[T](
      params: TextDocumentPositionParams,
      interactiveSemanticdbs: Option[InteractiveSemanticdbs]
  )(fn: (PresentationCompiler, Position) => T): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    loadCompiler(path, interactiveSemanticdbs).map { compiler =>
      def defaultInputPos = {
        val input = path
          .toInputFromBuffers(buffers)
          .copy(path = params.getTextDocument.getUri())
        val pos = params.getPosition
        (input, pos)
      }

      val (input, paramsPos) =
        ammoniteInputPosOpt(path, params.getPosition, interactiveSemanticdbs)
          .getOrElse(defaultInputPos)
      val pos = paramsPos.toMeta(input)

      fn(compiler, pos)
    }
  }

  private def configure(
      pc: PresentationCompiler,
      search: SymbolSearch
  ): PresentationCompiler =
    pc.withSearch(search)
      .withExecutorService(ec)
      .withScheduledExecutorService(sh)
      .withConfiguration(
        initializeParams
          .map(params => {
            val options = InitializationOptions.from(params).compilerOptions
            config.compilers.update(options)
          })
          .getOrElse(config.compilers)
          .copy(
            _symbolPrefixes = userConfig().symbolPrefixes,
            isCompletionSnippetsEnabled =
              initializeParams.supportsCompletionSnippets,
            isFoldOnlyLines = initializeParams.foldOnlyLines,
            isStripMarginOnTypeFormattingEnabled =
              userConfig().enableStripMarginOnTypeFormatting
          )
      )

  def newCompiler(
      scalac: ScalacOptionsItem,
      info: ScalaBuildTarget
  ): PresentationCompiler = {
    val classpath = scalac.classpath.map(_.toNIO).toSeq
    // The metals_2.12 artifact depends on mtags_2.12.x where "x" matches
    // `mtags.BuildInfo.scalaCompilerVersion`. In the case when
    // `info.getScalaVersion == mtags.BuildInfo.scalaCompilerVersion` then we
    // skip fetching the mtags module from Maven.
    val pc: PresentationCompiler =
      if (ScalaVersions.isCurrentScalaCompilerVersion(info.getScalaVersion())) {
        new ScalaPresentationCompiler()
      } else {
        embedded.presentationCompiler(info, scalac)
      }
    val options = plugins.filterSupportedOptions(scalac.getOptions.asScala)
    configure(pc, search).newInstance(
      scalac.getTarget.getUri,
      classpath.asJava,
      (log ++ options).asJava
    )
  }
}
