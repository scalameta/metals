package scala.meta.internal.metals

import java.net.URI
import java.net.URLClassLoader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.SourcesParams
import com.google.gson.JsonElement
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j._
import org.eclipse.{lsp4j => l}
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.tvp._
import scala.meta.internal.mtags._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.pc.CancelToken
import scala.meta.tokenizers.TokenizeException
import scala.util.control.NonFatal
import scala.util.Success
import com.google.gson.JsonPrimitive
import com.google.gson.JsonObject

class MetalsLanguageServer(
    ec: ExecutionContextExecutorService,
    buffers: Buffers = Buffers(),
    redirectSystemOut: Boolean = true,
    charset: Charset = StandardCharsets.UTF_8,
    time: Time = Time.system,
    config: MetalsServerConfig = MetalsServerConfig.default,
    progressTicks: ProgressTicks = ProgressTicks.braille,
    bspGlobalDirectories: List[AbsolutePath] =
      BspServers.globalInstallDirectories,
    sh: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    newBloopClassloader: () => URLClassLoader = () =>
      Embedded.newBloopClassloader()
) extends Cancelable {
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)
  private val cancelables = new MutableCancelable()
  val isCancelled = new AtomicBoolean(false)
  override def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      val buildShutdown = buildServer match {
        case Some(build) => build.shutdown()
        case None => Future.successful(())
      }
      try cancelables.cancel()
      catch {
        case NonFatal(_) =>
      }
      try buildShutdown.asJava.get(100, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
      }
    }
  }

  def cancelAll(): Unit = {
    cancel()
    Cancelable.cancelAll(
      List(
        Cancelable(() => ec.shutdown()),
        Cancelable(() => sh.shutdown())
      )
    )
  }

  private implicit val executionContext: ExecutionContextExecutorService = ec

  private val fingerprints = new MutableMd5Fingerprints
  private val mtags = new Mtags
  var workspace: AbsolutePath = _
  var focusedDocument = Option.empty[AbsolutePath]
  private val definitionIndex = newSymbolIndex()
  private val symbolDocs = new Docstrings(definitionIndex)
  var buildServer = Option.empty[BuildServerConnection]
  private val buildTargetClasses = new BuildTargetClasses(() => buildServer)
  private val openTextDocument = new AtomicReference[AbsolutePath]()
  private val savedFiles = new ActiveFiles(time)
  private val openedFiles = new ActiveFiles(time)
  private val messages = new Messages(config.icons)
  private val languageClient =
    new DelegatingLanguageClient(NoopLanguageClient, config)
  var userConfig = UserConfiguration()
  val buildTargets: BuildTargets = new BuildTargets()
  val compilations: Compilations = new Compilations(
    buildTargets,
    buildTargetClasses,
    () => workspace,
    () => buildServer
  )
  private val fileWatcher = register(
    new FileWatcher(
      buildTargets,
      params => didChangeWatchedFiles(params)
    )
  )
  private val indexingPromise = Promise[Unit]()
  val parseTrees = new BatchedFunction[AbsolutePath, Unit](
    paths => CancelableFuture(paths.distinct.foreach(trees.didChange))
  )
  private val onBuildChanged =
    BatchedFunction.fromFuture[AbsolutePath, BuildChange](
      onBuildChangedUnbatched
    )
  val pauseables = Pauseable.fromPausables(
    onBuildChanged :: parseTrees :: compilations.pauseables
  )

  // These can't be instantiated until we know the workspace root directory.
  private var bloopInstall: BloopInstall = _
  private var diagnostics: Diagnostics = _
  private var warnings: Warnings = _
  private var trees: Trees = _
  private var documentSymbolProvider: DocumentSymbolProvider = _
  private var fileSystemSemanticdbs: FileSystemSemanticdbs = _
  private var interactiveSemanticdbs: InteractiveSemanticdbs = _
  private var buildTools: BuildTools = _
  private var semanticdbs: Semanticdbs = _
  private var buildClient: ForwardingMetalsBuildClient = _
  private var bloopServers: BloopServers = _
  private var bspServers: BspServers = _
  private var codeLensProvider: CodeLensProvider = _
  private var definitionProvider: DefinitionProvider = _
  private var documentHighlightProvider: DocumentHighlightProvider = _
  private var formattingProvider: FormattingProvider = _
  private var initializeParams: Option[InitializeParams] = None
  private var referencesProvider: ReferenceProvider = _
  private var workspaceSymbols: WorkspaceSymbolProvider = _
  private var foldingRangeProvider: FoldingRangeProvider = _
  private var compilers: Compilers = _
  var tables: Tables = _
  var statusBar: StatusBar = _
  private var embedded: Embedded = _
  private var doctor: Doctor = _
  var httpServer: Option[MetalsHttpServer] = None
  var treeView: TreeViewProvider = NoopTreeViewProvider

  def connectToLanguageClient(client: MetalsLanguageClient): Unit = {
    languageClient.underlying = client
    statusBar = new StatusBar(
      () => languageClient,
      time,
      progressTicks,
      config.icons,
      config.statusBar
    )
    embedded = register(
      new Embedded(
        config.icons,
        statusBar,
        () => userConfig,
        newBloopClassloader
      )
    )
    LanguageClientLogger.languageClient = Some(languageClient)
    cancelables.add(() => languageClient.shutdown())
  }

  def register[T <: Cancelable](cancelable: T): T = {
    cancelables.add(cancelable)
    cancelable
  }

  private def updateWorkspaceDirectory(params: InitializeParams): Unit = {
    workspace = AbsolutePath(Paths.get(URI.create(params.getRootUri))).dealias
    MetalsLogger.setupLspLogger(workspace, redirectSystemOut)
    buildTargets.setWorkspaceDirectory(workspace)
    tables = register(new Tables(workspace, time, config))
    buildTargets.setTables(tables)
    buildTools = new BuildTools(workspace, bspGlobalDirectories)
    fileSystemSemanticdbs = new FileSystemSemanticdbs(
      buildTargets,
      charset,
      workspace,
      fingerprints
    )
    interactiveSemanticdbs = register(
      new InteractiveSemanticdbs(
        workspace,
        buildTargets,
        charset,
        languageClient,
        tables,
        messages,
        statusBar,
        () => compilers,
        config
      )
    )
    warnings = new Warnings(
      workspace,
      buildTargets,
      statusBar,
      config.icons,
      buildTools,
      compilations.isCurrentlyCompiling
    )
    diagnostics = new Diagnostics(
      buildTargets,
      buffers,
      languageClient,
      config.statistics,
      () => userConfig
    )
    buildClient = new ForwardingMetalsBuildClient(
      languageClient,
      diagnostics,
      buildTargets,
      config,
      statusBar,
      time,
      report => compilers.didCompile(report),
      () => treeView
    )
    trees = new Trees(buffers, diagnostics)
    documentSymbolProvider = new DocumentSymbolProvider(trees)
    bloopInstall = register(
      new BloopInstall(
        workspace,
        languageClient,
        sh,
        buildTools,
        time,
        tables,
        messages,
        config,
        embedded,
        statusBar,
        () => userConfig
      )
    )
    bloopServers = new BloopServers(
      sh,
      workspace,
      buildClient,
      config,
      config.icons,
      embedded,
      statusBar
    )
    bspServers = new BspServers(
      workspace,
      charset,
      languageClient,
      buildClient,
      tables,
      bspGlobalDirectories
    )
    semanticdbs = AggregateSemanticdbs(
      List(
        fileSystemSemanticdbs,
        interactiveSemanticdbs
      )
    )
    codeLensProvider = new CodeLensProvider(
      buildTargetClasses,
      buffers,
      buildTargets,
      semanticdbs
    )
    definitionProvider = new DefinitionProvider(
      workspace,
      mtags,
      buffers,
      definitionIndex,
      semanticdbs,
      config.icons,
      statusBar,
      warnings,
      () => compilers
    )
    formattingProvider = new FormattingProvider(
      workspace,
      buffers,
      embedded,
      config,
      () => userConfig,
      languageClient,
      statusBar,
      config.icons,
      Option(params.getWorkspaceFolders) match {
        case Some(folders) =>
          folders.asScala.map(_.getUri.toAbsolutePath).toList
        case _ =>
          Nil
      }
    )
    referencesProvider = new ReferenceProvider(
      workspace,
      semanticdbs,
      buffers,
      definitionProvider
    )
    documentHighlightProvider = new DocumentHighlightProvider(
      definitionProvider,
      semanticdbs
    )
    workspaceSymbols = new WorkspaceSymbolProvider(
      workspace,
      config.statistics,
      buildTargets,
      definitionIndex,
      pkg => {
        val mightContain =
          referencesProvider.referencedPackages.mightContain(pkg)
        if (mightContain) 0 else 1
      },
      interactiveSemanticdbs.toFileOnDisk
    )
    foldingRangeProvider = FoldingRangeProvider(trees, buffers, params)
    compilers = register(
      new Compilers(
        workspace,
        config,
        () => userConfig,
        buildTargets,
        buffers,
        new MetalsSymbolSearch(
          symbolDocs,
          workspaceSymbols,
          definitionProvider
        ),
        embedded,
        statusBar,
        sh
      )
    )
    doctor = new Doctor(
      workspace,
      buildTargets,
      config,
      languageClient,
      () => httpServer,
      tables,
      messages
    )
    if (isTreeViewSupported(params)) {
      treeView = new MetalsTreeViewProvider(
        () => workspace,
        languageClient,
        buildTargets,
        () => buildClient.ongoingCompilations(),
        definitionIndex,
        config.statistics,
        id => compilations.compileTargets(List(id)),
        sh
      )
    }
  }

  def setupJna(): Unit = {
    // This is required to avoid the following error:
    //   java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.platform.win32.Kernel32
    //     at sbt.internal.io.WinMilli$.getHandle(Milli.scala:277)
    //   There is an incompatible JNA native library installed on this system
    //     Expected: 5.2.2
    //     Found:    3.2.1
    System.setProperty("jna.nosys", "true")
  }

  @JsonRequest("initialize")
  def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult] = {
    timed("initialize")(Future {
      setupJna()
      initializeParams = Option(params)
      updateWorkspaceDirectory(params)
      val capabilities = new ServerCapabilities()
      capabilities.setExecuteCommandProvider(
        new ExecuteCommandOptions(
          ServerCommands.all.map(_.id).asJava
        )
      )
      capabilities.setFoldingRangeProvider(true)
      capabilities.setDefinitionProvider(true)
      capabilities.setHoverProvider(true)
      capabilities.setReferencesProvider(true)
      capabilities.setDocumentHighlightProvider(true)
      capabilities.setSignatureHelpProvider(
        new SignatureHelpOptions(List("(", "[").asJava)
      )
      capabilities.setCompletionProvider(
        new CompletionOptions(
          config.compilers.isCompletionItemResolve,
          List(".").asJava
        )
      )
      capabilities.setWorkspaceSymbolProvider(true)
      capabilities.setDocumentSymbolProvider(true)
      capabilities.setDocumentFormattingProvider(true)
      capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
      if (config.isNoInitialized) {
        sh.schedule(
          () => initialized(new InitializedParams),
          1,
          TimeUnit.SECONDS
        )
      }
      capabilities.setExperimental(MetalsExperimental())
      new InitializeResult(capabilities)
    }).asJava
  }

  private def isTreeViewSupported(params: InitializeParams): Boolean = {
    params.getCapabilities() != null &&
    params.getCapabilities().getExperimental() != null && {
      params.getCapabilities().getExperimental() match {
        case json: JsonObject => json.has("treeViewProvider")
        case _ => false
      }
    }
  }

  private def registerNiceToHaveFilePatterns(): Unit = {
    for {
      params <- initializeParams
      capabilities <- Option(params.getCapabilities)
      workspace <- Option(capabilities.getWorkspace)
      didChangeWatchedFiles <- Option(workspace.getDidChangeWatchedFiles)
      if didChangeWatchedFiles.getDynamicRegistration
    } yield {
      languageClient.registerCapability(
        new RegistrationParams(
          List(
            new Registration(
              "1",
              "workspace/didChangeWatchedFiles",
              config.globSyntax.registrationOptions(this.workspace)
            )
          ).asJava
        )
      )
    }
  }

  private def startHttpServer(): Unit = {
    if (config.isHttpEnabled) {
      val host = "localhost"
      val port = 5031
      var url = s"http://$host:$port"
      var render: () => String = () => ""
      var completeCommand: HttpServerExchange => Unit = e => ()
      val server = register(
        MetalsHttpServer(
          host,
          port,
          this,
          () => render(),
          e => completeCommand(e),
          () => doctor.problemsHtmlPage(url)
        )
      )
      httpServer = Some(server)
      val newClient = new MetalsHttpClient(
        workspace,
        () => url,
        languageClient.underlying,
        () => server.reload(),
        charset,
        config.icons,
        time,
        sh,
        config
      )
      render = () => newClient.renderHtml
      completeCommand = e => newClient.completeCommand(e)
      languageClient.underlying = newClient
      server.start()
      url = server.address
    }
  }

  val isInitialized = new AtomicBoolean(false)
  @JsonNotification("initialized")
  def initialized(params: InitializedParams): CompletableFuture[Unit] = {
    // Avoid duplicate `initialized` notifications. During the transition
    // for https://github.com/natebosch/vim-lsc/issues/113 to get fixed,
    // we may have users on a fixed vim-lsc version but with -Dmetals.no-initialized=true
    // enabled.
    if (isInitialized.compareAndSet(false, true)) {
      statusBar.start(sh, 0, 1, TimeUnit.SECONDS)
      tables.connect()
      registerNiceToHaveFilePatterns()
      val result = Future
        .sequence(
          List[Future[Unit]](
            quickConnectToBuildServer().ignoreValue,
            slowConnectToBuildServer(forceImport = false).ignoreValue,
            Future(workspaceSymbols.indexClasspath()),
            Future(startHttpServer()),
            Future(formattingProvider.load())
          )
        )
        .ignoreValue
      result
    } else {
      scribe.warn("Ignoring duplicate 'initialized' notification.")
      Future.successful(())
    }
  }.recover {
    case NonFatal(e) =>
      scribe.error("Unexpected error initializing server", e)
  }.asJava

  lazy val shutdownPromise = new AtomicReference[Promise[Unit]](null)
  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[Unit] = {
    val promise = Promise[Unit]()
    // Ensure we only run `shutdown` at most once and that `exit` waits for the
    // `shutdown` promise to complete.
    if (shutdownPromise.compareAndSet(null, promise)) {
      scribe.info("shutting down Metals")
      try {
        cancel()
      } catch {
        case NonFatal(e) =>
          scribe.error("cancellation error", e)
      } finally {
        promise.success(())
      }
      if (config.isExitOnShutdown) {
        System.exit(0)
      }
      promise.future.asJava
    } else {
      shutdownPromise.get().future.asJava
    }
  }

  @JsonNotification("exit")
  def exit(): Unit = {
    // `shutdown` is idempotent, we can trigger it as often as we like.
    shutdown()
    // Ensure that `shutdown` has completed before killing the process.
    // Some clients may send `exit` immediately after `shutdown` causing
    // the build server to get killed before it can clean up resources.
    try {
      Await.result(
        shutdownPromise.get().future,
        Duration(3, TimeUnit.SECONDS)
      )
    } catch {
      case NonFatal(e) =>
        scribe.error("shutdown error", e)
    } finally {
      System.exit(0)
    }
  }

  @JsonNotification("textDocument/didOpen")
  def didOpen(params: DidOpenTextDocumentParams): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    openedFiles.add(path)
    openTextDocument.set(path)
    // Update md5 fingerprint from file contents on disk
    fingerprints.add(path, FileIO.slurp(path, charset))
    // Update in-memory buffer contents from LSP client
    buffers.put(path, params.getTextDocument.getText)
    trees.didChange(path)
    if (path.isDependencySource(workspace)) {
      CancelTokens { _ =>
        // trigger compilation in preparation for definition requests
        interactiveSemanticdbs.textDocument(path)
        // publish diagnostics
        interactiveSemanticdbs.didFocus(path)
        ()
      }
    } else {
      compilers.load(List(path))
      compilations.compileFiles(List(path)).ignoreValue.asJava
    }
  }

  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(uri: String): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    focusedDocument = Some(path)
    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
    Future(treeView.didFocusTextDocument(path))
    // Don't trigger compilation on didFocus events under cascade compilation
    // because save events already trigger compile in inverse dependencies.
    if (path.isDependencySource(workspace)) {
      CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    } else if (openedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(DidFocusResult.RecentlyActive)
    } else {
      buildTargets.inverseSources(path) match {
        case Some(target) =>
          val isAffectedByCurrentCompilation =
            buildTargets.isInverseDependency(
              target,
              compilations.currentlyCompiling.toList
            )
          def isAffectedByLastCompilation: Boolean =
            !compilations.wasPreviouslyCompiled(target) &&
              buildTargets.isInverseDependency(
                target,
                compilations.previouslyCompiled.toList
              )
          val needsCompile = isAffectedByCurrentCompilation || isAffectedByLastCompilation
          if (needsCompile) {
            compilations
              .compileFiles(List(path))
              .map(_ => DidFocusResult.Compiled)
              .asJava
          } else {
            CompletableFuture.completedFuture(DidFocusResult.AlreadyCompiled)
          }
        case None =>
          CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
      }
    }
  }

  @JsonNotification("metals/windowStateDidChange")
  def windowStateDidChange(params: WindowStateDidChangeParams): Unit = {
    if (params.focused) {
      pauseables.unpause()
    } else {
      pauseables.pause()
    }
  }

  @JsonNotification("textDocument/didChange")
  def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] =
    params.getContentChanges.asScala.headOption match {
      case None => CompletableFuture.completedFuture(())
      case Some(change) =>
        val path = params.getTextDocument.getUri.toAbsolutePath
        buffers.put(path, change.getText)
        diagnostics.didChange(path)
        parseTrees(path).asJava
    }

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    buffers.remove(path)
    trees.didClose(path)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    savedFiles.add(path)
    // read file from disk, we only remove files from buffers on didClose.
    buffers.put(path, path.toInput.text)
    Future
      .sequence(List(parseTrees(path), onChange(List(path))))
      .ignoreValue
      .asJava
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] =
    Future {
      val json = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
      UserConfiguration.fromJson(json) match {
        case Left(errors) =>
          errors.foreach { error =>
            scribe.error(s"config error: $error")
          }
        case Right(value) =>
          val old = userConfig
          userConfig = value
          if (userConfig.symbolPrefixes != old.symbolPrefixes) {
            compilers.restartAll()
          }
      }
    }.asJava

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] = {
    val paths = params.getChanges.asScala.iterator
      .map(_.getUri.toAbsolutePath)
      .filterNot(savedFiles.isRecentlyActive) // de-duplicate didSave events.
      .toSeq
    onChange(paths).asJava
  }

  def didChangeWatchedFiles(
      event: DirectoryChangeEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path())
    val isScalaOrJava = path.isScalaOrJava
    if (isScalaOrJava && event.eventType() == EventType.DELETE) {
      diagnostics.didDelete(path)
      CompletableFuture.completedFuture(())
    } else if (isScalaOrJava && !savedFiles.isRecentlyActive(path)) {
      event.eventType() match {
        case EventType.CREATE =>
          buildTargets.onCreate(path)
        case _ =>
      }
      onChange(List(path)).asJava
    } else if (path.isSemanticdb) {
      CompletableFuture.completedFuture {
        event.eventType() match {
          case EventType.DELETE =>
            referencesProvider.onDelete(event.path())
          case EventType.CREATE | EventType.MODIFY =>
            referencesProvider.onChange(event.path())
          case EventType.OVERFLOW =>
            referencesProvider.onOverflow(event.path())
        }
      }
    } else {
      CompletableFuture.completedFuture(())
    }
  }

  private def onChange(paths: Seq[AbsolutePath]): Future[Unit] = {
    paths.foreach { path =>
      fingerprints.add(path, FileIO.slurp(path, charset))
    }
    Future
      .sequence(
        List(
          Future(reindexWorkspaceSources(paths)),
          compilations.compileFiles(paths).ignoreValue,
          onBuildChanged(paths).ignoreValue
        )
      )
      .ignoreValue
  }

  @JsonRequest("textDocument/definition")
  def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens.future { token =>
      definitionOrReferences(position, token).map(_.locations)
    }

  @JsonRequest("textDocument/typeDefinition")
  def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens { _ =>
      scribe.warn("textDocument/typeDefinition is not supported.")
      null
    }

  @JsonRequest("textDocument/implementation")
  def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens { _ =>
      scribe.warn("textDocument/implementation is not supported.")
      null
    }

  @JsonRequest("textDocument/hover")
  def hover(params: TextDocumentPositionParams): CompletableFuture[Hover] =
    CancelTokens.future { token =>
      compilers
        .hover(params, token, interactiveSemanticdbs)
        .map(_.orNull)
    }

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CancelTokens { _ =>
      documentHighlightProvider.documentHighlight(params)
    }

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] =
    CancelTokens { _ =>
      val result = documentSymbolResult(params)
      if (initializeParams.supportsHierarchicalDocumentSymbols) {
        JEither.forLeft(result)
      } else {
        val infos = result.asScala
          .toSymbolInformation(params.getTextDocument.getUri)
          .asJava
        JEither.forRight(infos)
      }
    }

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token =>
      formattingProvider.format(
        params.getTextDocument.getUri.toAbsolutePath,
        token
      )
    }

  @JsonRequest("textDocument/rename")
  def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit] =
    CancelTokens { _ =>
      scribe.warn("textDocument/rename is not supported.")
      null
    }

  @JsonRequest("textDocument/references")
  def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens { _ =>
      referencesResult(params).locations.asJava
    }

  // Triggers a cascade compilation and tries to find new references to a given symbol.
  // It's not possible to stream reference results so if we find new symbols we notify the
  // user to run references again to see updated results.
  private def compileAndLookForNewReferences(
      params: ReferenceParams,
      result: ReferencesResult
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val old = path.toInputFromBuffers(buffers)
    compilations.cascadeCompileFiles(Seq(path)).foreach { _ =>
      val newBuffer = path.toInputFromBuffers(buffers)
      val newParams: Option[ReferenceParams] =
        if (newBuffer.text == old.text) Some(params)
        else {
          val edit = TokenEditDistance(old, newBuffer)
          edit
            .toRevised(
              params.getPosition.getLine,
              params.getPosition.getCharacter
            )
            .foldResult(
              pos => {
                params.getPosition.setLine(pos.startLine)
                params.getPosition.setCharacter(pos.startColumn)
                Some(params)
              },
              () => Some(params),
              () => None
            )
        }
      newParams match {
        case None =>
        case Some(p) =>
          val newResult = referencesProvider.references(p)
          val diff = newResult.locations.length - result.locations.length
          val isSameSymbol = newResult.symbol == result.symbol
          if (isSameSymbol && diff > 0) {
            import scala.meta.internal.semanticdb.Scala._
            val name = newResult.symbol.desc.name.value
            val message =
              s"Found new symbol references for '$name', try running again."
            scribe.info(message)
            statusBar.addMessage(config.icons.info + message)
          }
      }
    }
  }
  def referencesResult(params: ReferenceParams): ReferencesResult = {
    val timer = new Timer(time)
    val result = referencesProvider.references(params)
    if (config.statistics.isReferences) {
      if (result.symbol.isEmpty) {
        scribe.info(s"time: found 0 references in $timer")
      } else {
        scribe.info(
          s"time: found ${result.locations.length} references to symbol '${result.symbol}' in $timer"
        )
      }
    }
    if (result.symbol.nonEmpty) {
      compileAndLookForNewReferences(params, result)
    }
    result
  }
  @JsonRequest("textDocument/completion")
  def completion(params: CompletionParams): CompletableFuture[CompletionList] =
    CancelTokens.future { token =>
      compilers.completions(params, token)
    }

  @JsonRequest("completionItem/resolve")
  def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    CancelTokens.future { token =>
      if (config.compilers.isCompletionItemResolve) {
        compilers.completionItemResolve(item, token)
      } else {
        Future.successful(item)
      }
    }

  @JsonRequest("textDocument/signatureHelp")
  def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    CancelTokens.future { token =>
      compilers.signatureHelp(params, token, interactiveSemanticdbs)
    }

  @JsonRequest("textDocument/codeAction")
  def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[CodeAction]] =
    CancelTokens { _ =>
      scribe.warn("textDocument/codeAction is not supported.")
      null
    }

  @JsonRequest("textDocument/codeLens")
  def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    CancelTokens { _ =>
      scribe.warn("textDocument/codeLens is not supported.")
      null
    }

  @JsonRequest("textDocument/foldingRange")
  def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] = {
    CancelTokens { _ =>
      val sourceFile = params.getTextDocument.getUri.toAbsolutePath
      foldingRangeProvider.getRangedFor(sourceFile)
    }
  }

  @JsonRequest("workspace/symbol")
  def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    CancelTokens.future { token =>
      indexingPromise.future.map { _ =>
        val timer = new Timer(time)
        val result = workspaceSymbols.search(params.getQuery, token).asJava
        if (config.statistics.isWorkspaceSymbol) {
          scribe.info(
            s"time: found ${result.size()} results for query '${params.getQuery}' in $timer"
          )
        }
        result
      }
    }

  def workspaceSymbol(query: String): Seq[SymbolInformation] = {
    workspaceSymbols.search(query)
  }

  @JsonRequest("workspace/executeCommand")
  def executeCommand(params: ExecuteCommandParams): CompletableFuture[Object] =
    params.getCommand match {
      case ServerCommands.ScanWorkspaceSources() =>
        Future {
          indexWorkspaceSources()
        }.asJavaObject
      case ServerCommands.ImportBuild() =>
        slowConnectToBuildServer(forceImport = true).asJavaObject
      case ServerCommands.ConnectBuildServer() =>
        quickConnectToBuildServer().asJavaObject
      case ServerCommands.DisconnectBuildServer() =>
        disconnectOldBuildServer().asJavaObject
      case ServerCommands.RunDoctor() =>
        Future {
          doctor.executeRunDoctor()
        }.asJavaObject
      case ServerCommands.BspSwitch() =>
        (for {
          isSwitched <- bspServers.switchBuildServer()
          _ <- {
            if (isSwitched) quickConnectToBuildServer()
            else Future.successful(())
          }
        } yield ()).asJavaObject
      case ServerCommands.OpenBrowser(url) =>
        Future.successful(Urls.openBrowser(url)).asJavaObject
      case ServerCommands.CascadeCompile() =>
        compilations
          .cascadeCompileFiles(buffers.open.toSeq)
          .asJavaObject
      case ServerCommands.CancelCompile() =>
        Future {
          compilations.cancel()
          scribe.info("compilation cancelled")
        }.asJavaObject
      case ServerCommands.PresentationCompilerRestart() =>
        Future {
          compilers.restartAll()
        }.asJavaObject
      case ServerCommands.GotoLocation() =>
        Future {
          for {
            args <- Option(params.getArguments())
            argObject <- args.asScala.headOption
            arg = argObject.asInstanceOf[JsonPrimitive]
            if arg.isString()
            symbol = arg.getAsString()
            location <- definitionProvider.fromSymbol(symbol).asScala.headOption
          } {
            languageClient.metalsExecuteClientCommand(
              new ExecuteCommandParams(
                ClientCommands.GotoLocation.id,
                List(location: Object).asJava
              )
            )
          }
        }.asJavaObject
      case ServerCommands.GotoLog() =>
        Future {
          val log = workspace.resolve(Directories.log)
          val linesCount = log.readText.lines.size
          val pos = new l.Position(linesCount, 0)
          languageClient.metalsExecuteClientCommand(
            new ExecuteCommandParams(
              ClientCommands.GotoLocation.id,
              List(
                new Location(log.toURI.toString(), new l.Range(pos, pos)): Object
              ).asJava
            )
          )
        }.asJavaObject
      case cmd =>
        scribe.error(s"Unknown command '$cmd'")
        Future.successful(()).asJavaObject
    }

  @JsonRequest("metals/treeViewChildren")
  def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] = {
    Future {
      treeView.children(params)
    }.asJava
  }

  @JsonRequest("metals/treeViewParent")
  def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] = {
    Future {
      treeView.parent(params)
    }.asJava
  }

  @JsonNotification("metals/treeViewVisibilityDidChange")
  def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      treeView.onVisibilityDidChange(params)
    }.asJava

  @JsonNotification("metals/treeViewNodeCollapseDidChange")
  def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      treeView.onCollapseDidChange(params)
    }.asJava

  @JsonRequest("metals/treeViewReveal")
  def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult] =
    Future {
      treeView
        .reveal(
          params.getTextDocument().getUri().toAbsolutePath,
          params.getPosition()
        )
        .orNull
    }.asJava

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    buildTools.loadSupported match {
      case Some(buildTool) =>
        if (BuildTool.isCompatibleVersion(
            buildTool.minimumVersion,
            buildTool.version
          )) {
          buildTool.digest(workspace) match {
            case None =>
              scribe.warn(s"Skipping build import, no checksum.")
              Future.successful(BuildChange.None)
            case Some(digest) =>
              slowConnectToBuildServer(forceImport, buildTool, digest)
          }
        } else {
          scribe.warn(
            s"Skipping build import for unsupported $buildTool version ${buildTool.version}"
          )
          languageClient.showMessage(
            messages.IncompatibleBuildToolVersion.params(buildTool)
          )
          Future.successful(BuildChange.None)
        }
      case None =>
        if (!buildTools.isAutoConnectable) {
          warnings.noBuildTool()
        }
        Future.successful(BuildChange.None)
    }
  }

  private def slowConnectToBuildServer(
      forceImport: Boolean,
      buildTool: BuildTool,
      checksum: String
  ): Future[BuildChange] =
    for {
      result <- {
        if (forceImport) bloopInstall.runUnconditionally(buildTool)
        else bloopInstall.runIfApproved(buildTool, checksum)
      }
      change <- {
        if (result.isInstalled) quickConnectToBuildServer()
        else if (result.isFailed) {
          if (buildTools.isAutoConnectable) {
            // TODO(olafur) try to connect but gracefully error
            languageClient.showMessage(
              messages.ImportProjectPartiallyFailed
            )
            // Connect nevertheless, many build import failures are caused
            // by resolution errors in one weird module while other modules
            // exported successfully.
            quickConnectToBuildServer()
          } else {
            languageClient.showMessage(messages.ImportProjectFailed)
            Future.successful(BuildChange.Failed)
          }
        } else {
          Future.successful(BuildChange.None)
        }
      }
    } yield change

  private def quickConnectToBuildServer(): Future[BuildChange] = {
    if (!buildTools.isAutoConnectable) {
      Future.successful(BuildChange.None)
    } else {
      autoConnectToBuildServer()
    }
  }

  private def autoConnectToBuildServer(): Future[BuildChange] = {
    for {
      _ <- disconnectOldBuildServer()
      maybeBuild <- timed("connected to build server") {
        if (buildTools.isBloop) bloopServers.newServer()
        else bspServers.newServer()
      }
      result <- maybeBuild match {
        case Some(build) =>
          connectToNewBuildServer(build)
        case None =>
          Future.successful(BuildChange.None)
      }
      _ = {
        treeView.init()
        focusedDocument.foreach(treeView.didFocusTextDocument)
      }
    } yield result
  }.recover {
    case NonFatal(e) =>
      disconnectOldBuildServer()
      val message =
        "Failed to connect with build server, no functionality will work."
      val details = " See logs for more details."
      languageClient.showMessage(
        new MessageParams(MessageType.Error, message + details)
      )
      scribe.error(message, e)
      BuildChange.Failed
  }

  private def disconnectOldBuildServer(): Future[Unit] = {
    if (buildServer.isDefined) {
      scribe.info("disconnected: build server")
    }
    buildServer match {
      case None => Future.successful(())
      case Some(value) =>
        buildServer = None
        diagnostics.reset()
        value.shutdown()
    }
  }
  private def connectToNewBuildServer(
      build: BuildServerConnection
  ): Future[BuildChange] = {
    cancelables.add(build)
    compilers.cancel()
    buildServer = Some(build)
    val importedBuild = timed("imported build") {
      for {
        workspaceBuildTargets <- build.server.workspaceBuildTargets().asScala
        ids = workspaceBuildTargets.getTargets.map(_.getId)
        scalacOptions <- build.server
          .buildTargetScalacOptions(new ScalacOptionsParams(ids))
          .asScala
        sources <- build.server
          .buildTargetSources(new SourcesParams(ids))
          .asScala
        dependencySources <- build.server
          .buildTargetDependencySources(new DependencySourcesParams(ids))
          .asScala
      } yield {
        ImportedBuild(
          workspaceBuildTargets,
          scalacOptions,
          sources,
          dependencySources
        )
      }
    }
    for {
      i <- statusBar.trackFuture("Importing build", importedBuild)
      _ <- profiledIndexWorkspace { () =>
        indexWorkspace(i)
      }
      _ = indexingPromise.trySuccess(())
      _ <- Future.sequence[Unit, List](
        compilations
          .cascadeCompileFiles(buffers.open.toSeq)
          .ignoreValue ::
          compilers.load(buffers.open.toSeq) ::
          Nil
      )
    } yield {
      BuildChange.Reconnected
    }
  }

  private def indexWorkspaceSources(): Unit = {
    for {
      (sourceItem, targets) <- buildTargets.sourceItemsToBuildTargets
      source <- ListFiles(sourceItem)
      if source.isScalaOrJava
    } {
      targets.asScala.foreach { target =>
        buildTargets.linkSourceFile(target, source)
      }
      indexSourceFile(source, Some(sourceItem))
    }
  }

  private def reindexWorkspaceSources(
      paths: Seq[AbsolutePath]
  ): Unit = {
    for {
      path <- paths.iterator
      if path.isScalaOrJava
    } {
      indexSourceFile(path, buildTargets.inverseSourceItem(path))
    }
  }

  private def indexSourceFile(
      source: AbsolutePath,
      sourceItem: Option[AbsolutePath]
  ): Unit = {
    try {
      val reluri = source.toIdeallyRelativeURI(sourceItem)
      val input = source.toInput
      val symbols = ArrayBuffer.empty[WorkspaceSymbolInformation]
      SemanticdbDefinition.foreach(input) {
        case SemanticdbDefinition(info, occ, owner) =>
          if (WorkspaceSymbolProvider.isRelevantKind(info.kind)) {
            occ.range.foreach { range =>
              symbols += WorkspaceSymbolInformation(
                info.symbol,
                info.kind,
                range.toLSP
              )
            }
          }
          if (sourceItem.isDefined &&
            !info.symbol.isPackage &&
            owner.isPackage) {
            definitionIndex.addToplevelSymbol(reluri, source, info.symbol)
          }
      }
      workspaceSymbols.didChange(source, symbols)
    } catch {
      case NonFatal(e) =>
        scribe.error(source.toString(), e)
    }
  }

  private def timed[T](
      didWhat: String,
      reportStatus: Boolean = false
  )(thunk: => Future[T]): Future[T] = {
    withTimer(didWhat, reportStatus)(thunk).map {
      case (_, value) => value
    }
  }

  def timedThunk[T](didWhat: String, onlyIf: Boolean = true)(thunk: => T): T = {
    val elapsed = new Timer(time)
    val result = thunk
    if (onlyIf) {
      scribe.info(s"time: $didWhat in $elapsed")
    }
    result
  }

  private def withTimer[T](didWhat: String, reportStatus: Boolean = false)(
      thunk: => Future[T]
  ): Future[(Timer, T)] = {
    val elapsed = new Timer(time)
    val result = thunk
    result.map { value =>
      if (elapsed.isLogWorthy) {
        scribe.info(s"time: $didWhat in $elapsed")
      }
      (elapsed, value)
    }
  }

  def profiledIndexWorkspace(
      thunk: () => Unit
  ): Future[Unit] = {
    val tracked = statusBar.trackFuture(
      s"Indexing",
      Future {
        timedThunk("indexed workspace", onlyIf = true) {
          try thunk()
          catch {
            case NonFatal(e) =>
              scribe.error("unexpected error indexing workspace", e)
          }
        }
      }
    )
    tracked.foreach { _ =>
      statusBar.addMessage(s"${config.icons.rocket}Indexing complete!")
      if (config.statistics.isMemory) {
        logMemory(
          "definition index",
          definitionIndex
        )
        logMemory(
          "references index",
          referencesProvider.index
        )
        logMemory(
          "workspace symbol index",
          workspaceSymbols.inWorkspace
        )
        logMemory(
          "classpath symbol index",
          workspaceSymbols.inDependencies.packages
        )
      }
    }
    tracked
  }

  private def logMemory(name: String, index: Object): Unit = {
    val footprint = Memory.footprint(name, index)
    scribe.info(s"memory: $footprint")
  }

  def indexWorkspace(i: ImportedBuild): Unit = {
    timedThunk("updated build targets", config.statistics.isIndex) {
      buildTargets.reset()
      interactiveSemanticdbs.reset()
      buildClient.reset()
      referencesProvider.reset()
      treeView.reset()
      buildTargets.addWorkspaceBuildTargets(i.workspaceBuildTargets)
      buildTargets.addScalacOptions(i.scalacOptions)
      for {
        item <- i.sources.getItems.asScala
        source <- item.getSources.asScala
      } {
        val sourceItemPath = source.getUri.toAbsolutePath
        buildTargets.addSourceItem(sourceItemPath, item.getTarget)
      }
      doctor.check()
    }
    timedThunk("started file watcher", config.statistics.isIndex) {
      fileWatcher.restart()
    }
    timedThunk(
      "indexed library classpath",
      config.statistics.isIndex
    ) {
      workspaceSymbols.indexClasspath()
    }
    timedThunk(
      "indexed workspace SemanticDBs",
      config.statistics.isIndex
    ) {
      referencesProvider.onScalacOptions(i.scalacOptions)
    }
    timedThunk(
      "indexed workspace sources",
      config.statistics.isIndex
    ) {
      indexWorkspaceSources()
    }
    timedThunk(
      "indexed library sources",
      config.statistics.isIndex
    ) {
      indexDependencySources(i.dependencySources)
    }
  }

  private def indexDependencySources(
      dependencySources: DependencySourcesResult
  ): Unit = {
    // Track used Jars so that we can
    // remove cached symbols from Jars
    // that are not used
    val usedJars = mutable.HashSet.empty[AbsolutePath]
    JdkSources(userConfig.javaHome) match {
      case Some(zip) =>
        usedJars += zip
        addSourceJarSymbols(zip)
      case None =>
        scribe.warn(
          s"Could not find java sources in ${userConfig.javaHome}. Java symbols will not be available."
        )
    }
    for {
      item <- dependencySources.getItems.asScala
      sourceUri <- Option(item.getSources).toList.flatMap(_.asScala)
    } {
      try {
        val path = sourceUri.toAbsolutePath
        buildTargets.addDependencySource(path, item.getTarget)
        if (path.isJar) {
          usedJars += path
          addSourceJarSymbols(path)
        } else {
          scribe.warn(s"unexpected dependency directory: $path")
        }
      } catch {
        case NonFatal(e) =>
          scribe.error(s"error processing $sourceUri", e)
      }
    }
    // Schedule removal of unused toplevel symbols from cache
    sh.schedule(
      new Runnable {
        override def run(): Unit = {
          tables.jarSymbols.deleteNotUsedTopLevels(usedJars.toArray)
        }
      },
      2,
      TimeUnit.SECONDS
    )
  }

  /**
   * Add top level Scala symbols from source JAR into symbol index
   * Uses H2 cache for symbols
   *
   * @param path JAR path
   */
  private def addSourceJarSymbols(path: AbsolutePath): Unit = {
    definitionIndex.addSourceJarTopLevels(
      path,
      () => {
        tables.jarSymbols.getTopLevels(path) match {
          case Some(toplevels) => toplevels
          case None =>
            // Nothing in cache, read top level symbols and store them in cache
            val tempIndex = OnDemandSymbolIndex()
            tempIndex.addSourceJar(path)
            tables.jarSymbols.putTopLevels(path, tempIndex.toplevels)
            tempIndex.toplevels
        }
      }
    )
  }

  private def onBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[BuildChange] = {
    val isBuildChange = paths.exists(buildTools.isBuildRelated(workspace, _))
    if (isBuildChange) {
      slowConnectToBuildServer(forceImport = false)
    } else {
      Future.successful(BuildChange.None)
    }
  }

  /**
   * Returns the the definition location or reference locations of a symbol
   * at a given text document position.
   * If the symbol represents the definition itself, this method returns
   * the reference locations, otherwise this returns definition location.
   * https://github.com/scalameta/metals/issues/755
   */
  def definitionOrReferences(
      position: TextDocumentPositionParams,
      token: CancelToken = EmptyCancelToken
  ): Future[DefinitionResult] = {
    val source = position.getTextDocument.getUri.toAbsolutePath
    if (source.toLanguage.isScala) {
      val semanticDBDoc =
        semanticdbs.textDocument(source).documentIncludingStale
      (for {
        doc <- semanticDBDoc
        positionOccurrence = definitionProvider.positionOccurrence(
          source,
          position,
          doc
        )
        occ <- positionOccurrence.occurrence
      } yield occ) match {
        case Some(occ) =>
          if (occ.role.isDefinition) {
            val referenceContext = new ReferenceContext(false)
            val refParams = new ReferenceParams(referenceContext)
            refParams.setTextDocument(position.getTextDocument())
            refParams.setPosition(position.getPosition())
            val result = referencesResult(refParams)
            if (result.locations.isEmpty) {
              // Fallback again to the original behavior that returns
              // the definition location itself if no reference locations found,
              // for avoiding the confusing messages like "No definition found ..."
              definitionResult(position, token)
            } else {
              Future.successful(
                DefinitionResult(
                  locations = result.locations.asJava,
                  symbol = result.symbol,
                  definition = None,
                  semanticdb = None
                )
              )
            }
          } else {
            definitionResult(position, token)
          }
        case None =>
          if (semanticDBDoc.isEmpty) {
            warnings.noSemanticdb(source)
          }
          // Even if it failed to retrieve the symbol occurrence from semanticdb,
          // try to find its definitions from presentation compiler.
          definitionResult(position, token)
      }
    } else {
      // Ignore non-scala files.
      Future.successful(DefinitionResult.empty)
    }
  }

  /**
   * Returns textDocument/definition in addition to the resolved symbol.
   *
   * The resolved symbol is used for testing purposes only.
   */
  def definitionResult(
      position: TextDocumentPositionParams,
      token: CancelToken = EmptyCancelToken
  ): Future[DefinitionResult] = {
    val source = position.getTextDocument.getUri.toAbsolutePath
    if (source.toLanguage.isScala) {
      val result = timedThunk("definition", config.statistics.isDefinition)(
        definitionProvider.definition(source, position, token)
      )
      result.onComplete {
        case Success(value) =>
          // Record what build target this dependency source (if any) was jumped from,
          // needed to know what classpath to compile the dependency source with.
          interactiveSemanticdbs.didDefinition(source, value)
        case _ =>
      }
      result
    } else {
      // Ignore non-scala files.
      Future.successful(DefinitionResult.empty)
    }
  }

  def documentSymbolResult(
      params: DocumentSymbolParams
  ): util.List[DocumentSymbol] = {
    documentSymbolProvider
      .documentSymbols(params.getTextDocument.getUri.toAbsolutePath)
  }

  private def newSymbolIndex(): OnDemandSymbolIndex = {
    OnDemandSymbolIndex(onError = {
      case e @ (_: ParseException | _: TokenizeException) =>
        scribe.error(e.toString)
      case NonFatal(e) =>
        scribe.error("unexpected error during source scanning", e)
    })
  }

}
