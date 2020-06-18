package scala.meta.internal.metals

import java.net.URI
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
import java.{util => ju}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.Supermethods
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.codelenses.RunTestCodeLens
import scala.meta.internal.metals.codelenses.SuperMethodCodeLens
import scala.meta.internal.metals.debug.DebugParametersJsonParsers
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.mtags._
import scala.meta.internal.remotels.RemoteLanguageServer
import scala.meta.internal.rename.RenameProvider
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semver.SemVer
import scala.meta.internal.tvp._
import scala.meta.internal.worksheets.DecorationWorksheetPublisher
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.internal.worksheets.WorkspaceEditWorksheetPublisher
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.pc.CancelToken
import scala.meta.tokenizers.TokenizeException

import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.{lsp4j => l}

class MetalsLanguageServer(
    ec: ExecutionContextExecutorService,
    buffers: Buffers = Buffers(),
    redirectSystemOut: Boolean = true,
    charset: Charset = StandardCharsets.UTF_8,
    time: Time = Time.system,
    initialConfig: MetalsServerConfig = MetalsServerConfig.default,
    progressTicks: ProgressTicks = ProgressTicks.braille,
    bspGlobalDirectories: List[AbsolutePath] =
      BspServers.globalInstallDirectories,
    sh: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    isReliableFileWatcher: Boolean = true
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
  var focusedDocument: Option[AbsolutePath] = None
  private val focusedDocumentBuildTarget =
    new AtomicReference[b.BuildTargetIdentifier]()
  private val definitionIndex = newSymbolIndex()
  private val symbolDocs = new Docstrings(definitionIndex)
  var buildServer: Option[BuildServerConnection] =
    Option.empty[BuildServerConnection]
  private def buildServerOf(
      target: b.BuildTargetIdentifier
  ): Option[BuildServerConnection] =
    if (Ammonite.isAmmBuildTarget(target)) ammonite.buildServer
    else buildServer
  private val savedFiles = new ActiveFiles(time)
  private val openedFiles = new ActiveFiles(time)
  private val languageClient = new DelegatingLanguageClient(NoopLanguageClient)
  var userConfig: UserConfiguration = UserConfiguration()
  val buildTargets: BuildTargets = new BuildTargets()
  private val buildTargetClasses =
    new BuildTargetClasses(buildServerOf, buildTargets)
  private val remote = new RemoteLanguageServer(
    () => workspace,
    () => userConfig,
    initialConfig,
    buffers,
    buildTargets
  )
  val compilations: Compilations = new Compilations(
    buildTargets,
    buildTargetClasses,
    () => workspace,
    buildServerOf,
    languageClient,
    buildTarget => focusedDocumentBuildTarget.get() == buildTarget,
    worksheets => onWorksheetChanged(worksheets)
  )
  private val fileWatcher = register(
    new FileWatcher(
      buildTargets,
      params => didChangeWatchedFiles(params)
    )
  )
  private val indexingPromise: Promise[Unit] = Promise[Unit]()
  val parseTrees = new BatchedFunction[AbsolutePath, Unit](paths =>
    CancelableFuture(
      Future.sequence(paths.distinct.map(compilers.didChange)).ignoreValue,
      Cancelable.empty
    )
  )
  private val onBuildChanged =
    BatchedFunction.fromFuture[AbsolutePath, BuildChange](
      onBuildChangedUnbatched
    )
  val pauseables: Pauseable = Pauseable.fromPausables(
    onBuildChanged ::
      parseTrees ::
      compilations.pauseables
  )

  // These can't be instantiated until we know the workspace root directory.
  private var shellRunner: ShellRunner = _
  private var bloopInstall: BloopInstall = _
  private var diagnostics: Diagnostics = _
  private var warnings: Warnings = _
  private var fileSystemSemanticdbs: FileSystemSemanticdbs = _
  private var interactiveSemanticdbs: InteractiveSemanticdbs = _
  private var buildTools: BuildTools = _
  private var newProjectProvider: NewProjectProvider = _
  private var semanticdbs: Semanticdbs = _
  private var buildClient: ForwardingMetalsBuildClient = _
  private var bloopServers: BloopServers = _
  private var bspServers: BspServers = _
  private var codeLensProvider: CodeLensProvider = _
  private var supermethods: Supermethods = _
  private var codeActionProvider: CodeActionProvider = _
  private var definitionProvider: DefinitionProvider = _
  private var semanticDBIndexer: SemanticdbIndexer = _
  private var implementationProvider: ImplementationProvider = _
  private var renameProvider: RenameProvider = _
  private var documentHighlightProvider: DocumentHighlightProvider = _
  private var formattingProvider: FormattingProvider = _
  private var initializeParams: Option[InitializeParams] = None
  private var referencesProvider: ReferenceProvider = _
  private var workspaceSymbols: WorkspaceSymbolProvider = _
  private val packageProvider: PackageProvider =
    new PackageProvider(buildTargets)
  private var newFilesProvider: NewFilesProvider = _
  private var debugProvider: DebugProvider = _
  private var symbolSearch: MetalsSymbolSearch = _
  private var compilers: Compilers = _
  def loadedPresentationCompilerCount(): Int =
    compilers.loadedPresentationCompilerCount()
  var tables: Tables = _
  var statusBar: StatusBar = _
  private var embedded: Embedded = _
  private var doctor: Doctor = _
  var httpServer: Option[MetalsHttpServer] = None
  var treeView: TreeViewProvider = NoopTreeViewProvider
  var worksheetProvider: WorksheetProvider = _
  var ammonite: Ammonite = _

  private val clientConfig: ClientConfiguration =
    new ClientConfiguration(
      initialConfig,
      ClientExperimentalCapabilities.Default,
      InitializationOptions.Default
    )

  def connectToLanguageClient(client: MetalsLanguageClient): Unit = {
    languageClient.underlying =
      new ConfiguredLanguageClient(client, clientConfig)(ec)
    statusBar = new StatusBar(
      () => languageClient,
      time,
      progressTicks,
      clientConfig
    )
    embedded = register(
      new Embedded(
        clientConfig.initialConfig.icons,
        statusBar,
        () => userConfig
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
    scribe.info(
      s"started: Metals version ${BuildInfo.metalsVersion} in workspace '$workspace'"
    )

    clientConfig.experimentalCapabilities =
      ClientExperimentalCapabilities.from(params.getCapabilities)
    clientConfig.initializationOptions = InitializationOptions.from(params)

    buildTargets.setWorkspaceDirectory(workspace)
    tables = register(new Tables(workspace, time, clientConfig))
    buildTargets.setTables(tables)
    buildTools = new BuildTools(
      workspace,
      bspGlobalDirectories,
      () => userConfig
    )
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
        statusBar,
        () => compilers,
        clientConfig
      )
    )
    warnings = new Warnings(
      workspace,
      buildTargets,
      statusBar,
      clientConfig.initialConfig.icons,
      buildTools,
      compilations.isCurrentlyCompiling
    )
    diagnostics = new Diagnostics(
      buildTargets,
      buffers,
      languageClient,
      clientConfig.initialConfig.statistics,
      () => userConfig
    )
    buildClient = new ForwardingMetalsBuildClient(
      languageClient,
      diagnostics,
      buildTargets,
      buildTargetClasses,
      clientConfig,
      statusBar,
      time,
      report => {
        didCompileTarget(report)
        compilers.didCompile(report)
      },
      () => treeView,
      () => worksheetProvider,
      () => ammonite
    )
    shellRunner = register(
      new ShellRunner(languageClient, () => userConfig, time, statusBar)
    )
    bloopInstall = new BloopInstall(
      workspace,
      languageClient,
      buildTools,
      tables,
      shellRunner
    )
    newProjectProvider = new NewProjectProvider(
      buildTools,
      languageClient,
      statusBar,
      clientConfig,
      time,
      shellRunner,
      initialConfig.icons,
      workspace
    )
    bloopServers = new BloopServers(
      workspace,
      buildClient,
      languageClient,
      tables,
      clientConfig.initialConfig
    )
    bspServers = new BspServers(
      workspace,
      charset,
      languageClient,
      buildClient,
      tables,
      bspGlobalDirectories,
      clientConfig.initialConfig
    )
    semanticdbs = AggregateSemanticdbs(
      List(
        fileSystemSemanticdbs,
        interactiveSemanticdbs
      )
    )
    definitionProvider = new DefinitionProvider(
      workspace,
      mtags,
      buffers,
      definitionIndex,
      semanticdbs,
      warnings,
      () => compilers,
      remote
    )
    formattingProvider = new FormattingProvider(
      workspace,
      buffers,
      () => userConfig,
      languageClient,
      clientConfig,
      statusBar,
      clientConfig.initialConfig.icons,
      Option(params.getWorkspaceFolders) match {
        case Some(folders) =>
          folders.asScala.map(_.getUri.toAbsolutePath).toList
        case _ =>
          Nil
      },
      tables
    )
    newFilesProvider = new NewFilesProvider(
      workspace,
      languageClient,
      packageProvider,
      () => focusedDocument
    )
    referencesProvider = new ReferenceProvider(
      workspace,
      semanticdbs,
      buffers,
      definitionProvider,
      remote
    )
    implementationProvider = new ImplementationProvider(
      semanticdbs,
      workspace,
      definitionIndex,
      buildTargets,
      buffers,
      definitionProvider
    )

    supermethods = new Supermethods(
      languageClient,
      definitionProvider,
      implementationProvider
    )

    val runTestLensProvider =
      new RunTestCodeLens(
        buildTargetClasses,
        buffers,
        buildTargets,
        clientConfig
      )

    val goSuperLensProvider = new SuperMethodCodeLens(
      implementationProvider,
      buffers,
      () => userConfig,
      clientConfig
    )
    codeLensProvider = new CodeLensProvider(
      List(runTestLensProvider, goSuperLensProvider),
      semanticdbs
    )
    renameProvider = new RenameProvider(
      referencesProvider,
      implementationProvider,
      definitionProvider,
      workspace,
      languageClient,
      buffers,
      compilations,
      clientConfig
    )
    semanticDBIndexer = new SemanticdbIndexer(
      referencesProvider,
      implementationProvider,
      buildTargets
    )
    documentHighlightProvider = new DocumentHighlightProvider(
      definitionProvider,
      semanticdbs
    )
    workspaceSymbols = new WorkspaceSymbolProvider(
      workspace,
      clientConfig.initialConfig.statistics,
      buildTargets,
      definitionIndex,
      interactiveSemanticdbs.toFileOnDisk
    )
    symbolSearch = new MetalsSymbolSearch(
      symbolDocs,
      workspaceSymbols,
      definitionProvider
    )
    compilers = register(
      new Compilers(
        workspace,
        clientConfig.initialConfig,
        () => userConfig,
        () => ammonite,
        buildTargets,
        buffers,
        symbolSearch,
        embedded,
        statusBar,
        sh,
        Option(params),
        diagnostics
      )
    )
    debugProvider = new DebugProvider(
      definitionProvider,
      buildServer,
      buildTargets,
      buildTargetClasses,
      compilations,
      languageClient,
      buildClient,
      statusBar,
      compilers
    )
    codeActionProvider = new CodeActionProvider(
      compilers,
      buffers
    )
    doctor = new Doctor(
      workspace,
      buildTargets,
      languageClient,
      () => httpServer,
      tables,
      clientConfig
    )
    val worksheetPublisher =
      if (clientConfig.isDecorationProvider)
        new DecorationWorksheetPublisher()
      else
        new WorkspaceEditWorksheetPublisher(buffers)
    worksheetProvider = register(
      new WorksheetProvider(
        workspace,
        buffers,
        buildTargets,
        languageClient,
        () => userConfig,
        statusBar,
        diagnostics,
        embedded,
        worksheetPublisher
      )
    )
    ammonite = register(
      new Ammonite(
        buffers,
        compilers,
        compilations,
        statusBar,
        diagnostics,
        doctor,
        () => tables,
        languageClient,
        buildClient,
        () => userConfig,
        () => profiledIndexWorkspace(() => ()),
        () => workspace,
        () => focusedDocument,
        buildTargets,
        () => buildTools,
        clientConfig.initialConfig
      )
    )
    if (clientConfig.isTreeViewProvider) {
      treeView = new MetalsTreeViewProvider(
        () => workspace,
        languageClient,
        buildTargets,
        () => buildClient.ongoingCompilations(),
        definitionIndex,
        clientConfig.initialConfig.statistics,
        id => compilations.compileTarget(id),
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
      capabilities.setCodeLensProvider(new CodeLensOptions(false))
      capabilities.setDefinitionProvider(true)
      capabilities.setImplementationProvider(true)
      capabilities.setHoverProvider(true)
      capabilities.setReferencesProvider(true)
      val renameOptions = new RenameOptions()
      renameOptions.setPrepareProvider(true)
      capabilities.setRenameProvider(renameOptions)
      capabilities.setDocumentHighlightProvider(true)
      capabilities.setDocumentOnTypeFormattingProvider(
        new DocumentOnTypeFormattingOptions("\n", List("\"").asJava)
      )
      capabilities.setDocumentRangeFormattingProvider(
        initialConfig.allowMultilineStringFormatting
      )
      capabilities.setSignatureHelpProvider(
        new SignatureHelpOptions(List("(", "[").asJava)
      )
      capabilities.setCompletionProvider(
        new CompletionOptions(
          clientConfig.isCompletionItemResolve,
          List(".", "*").asJava
        )
      )
      capabilities.setWorkspaceSymbolProvider(true)
      capabilities.setDocumentSymbolProvider(true)
      capabilities.setDocumentFormattingProvider(true)
      if (initializeParams.supportsCodeActionLiterals) {
        capabilities.setCodeActionProvider(
          new CodeActionOptions(
            List(CodeActionKind.QuickFix, CodeActionKind.Refactor).asJava
          )
        )
      } else {
        capabilities.setCodeActionProvider(true)
      }

      val textDocumentSyncOptions = new TextDocumentSyncOptions
      textDocumentSyncOptions.setChange(TextDocumentSyncKind.Full)
      textDocumentSyncOptions.setSave(new SaveOptions(true))
      textDocumentSyncOptions.setOpenClose(true)

      capabilities.setTextDocumentSync(textDocumentSyncOptions)

      val serverInfo = new ServerInfo("Metals", BuildInfo.metalsVersion)
      new InitializeResult(capabilities, serverInfo)
    }).asJava
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
              clientConfig.initialConfig.globSyntax.registrationOptions(
                this.workspace
              )
            )
          ).asJava
        )
      )
    }
  }

  private def startHttpServer(): Unit = {
    if (clientConfig.isHttpEnabled) {
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
        clientConfig.initialConfig.icons,
        time,
        sh,
        clientConfig
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
      if (clientConfig.isExitOnShutdown) {
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
    focusedDocument = Some(path)
    openedFiles.add(path)

    // Update md5 fingerprint from file contents on disk
    fingerprints.add(path, FileIO.slurp(path, charset))
    // Update in-memory buffer contents from LSP client
    buffers.put(path, params.getTextDocument.getText)
    val didChangeFuture = compilers.didChange(path)

    packageProvider
      .workspaceEdit(path)
      .map(new ApplyWorkspaceEditParams(_))
      .foreach(languageClient.applyEdit)

    if (path.isDependencySource(workspace)) {
      CancelTokens { _ =>
        // trigger compilation in preparation for definition requests
        interactiveSemanticdbs.textDocument(path)
        // publish diagnostics
        interactiveSemanticdbs.didFocus(path)
        ()
      }
    } else {
      if (path.isAmmoniteScript)
        ammonite.maybeImport(path)
      val loadFuture = compilers.load(List(path))
      val compileFuture =
        compilations.compileFile(path)
      Future
        .sequence(List(didChangeFuture, loadFuture, compileFuture))
        .ignoreValue
        .asJava
    }
  }
  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(uri: String): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    focusedDocument = Some(path)
    buildTargets
      .inverseSources(path)
      .foreach(focusedDocumentBuildTarget.set)

    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
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
            path.isWorksheet ||
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
          val needsCompile =
            isAffectedByCurrentCompilation || isAffectedByLastCompilation
          if (needsCompile) {
            compilations
              .compileFile(path)
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
    compilers.didClose(path)
    diagnostics.onNoSyntaxError(path)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    savedFiles.add(path)
    // read file from disk, we only remove files from buffers on didClose.
    buffers.put(path, path.toInput.text)
    Future
      .sequence(
        List(
          Future(renameProvider.runSave()),
          parseTrees(path),
          onChange(List(path))
        )
      )
      .ignoreValue
      .asJava
  }

  private def didCompileTarget(report: CompileReport): Unit = {
    if (!isReliableFileWatcher) {
      // NOTE(olafur) this step is exclusively used when running tests on
      // non-Linux computers to avoid flaky failures caused by delayed file
      // watching notifications. The SemanticDB indexer depends on file watching
      // notifications to pick up `*.semanticdb` file updates and there's no
      // reliable way to await until those notifications appear.
      for {
        item <- buildTargets.scalacOptions(report.getTarget())
        semanticdb = item.targetroot.resolve(Directories.semanticdb)
        generatedFile <- semanticdb.listRecursive
      } {
        val event =
          new DirectoryChangeEvent(EventType.MODIFY, generatedFile.toNIO, 1)
        didChangeWatchedFiles(event).get()
      }
    }
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] =
    Future {
      val json = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
      UserConfiguration.fromJson(json) match {
        case Left(errors) =>
          errors.foreach { error => scribe.error(s"config error: $error") }
          Future.successful(())
        case Right(value) =>
          val old = userConfig
          userConfig = value
          if (userConfig.symbolPrefixes != old.symbolPrefixes) {
            compilers.restartAll()
          }
          val expectedBloopVersion = userConfig.currentBloopVersion
          val correctVersionRunning =
            buildServer.map(_.version).contains(expectedBloopVersion)
          val allVersionsDefined =
            buildServer.nonEmpty && userConfig.bloopVersion.nonEmpty
          val changedToNoVersion =
            old.bloopVersion.isDefined && userConfig.bloopVersion.isEmpty
          val versionChanged = allVersionsDefined && !correctVersionRunning
          val versionRevertedToDefault =
            changedToNoVersion && !correctVersionRunning
          if (versionRevertedToDefault || versionChanged) {
            languageClient
              .showMessageRequest(
                Messages.BloopVersionChange.params()
              )
              .asScala
              .flatMap {
                case item if item == Messages.BloopVersionChange.reconnect =>
                  bloopServers.shutdownServer()
                  autoConnectToBuildServer().ignoreValue
                case _ =>
                  Future.successful(())
              }
          } else if (userConfig.pantsTargets != old.pantsTargets) {
            slowConnectToBuildServer(forceImport = false).ignoreValue
          } else {
            Future.successful(())
          }
      }
    }.flatten.asJava

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

  // This method is run the FileWatcher, so it should not do anything expensive on the main thread
  private def didChangeWatchedFiles(
      event: DirectoryChangeEvent
  ): CompletableFuture[Unit] = {
    if (event.eventType() == EventType.OVERFLOW && event.path() == null) {
      Future {
        semanticDBIndexer.onOverflow()
      }.asJava
    } else {
      val path = AbsolutePath(event.path())
      val isScalaOrJava = path.isScalaOrJava
      if (isScalaOrJava && event.eventType() == EventType.DELETE) {
        Future {
          diagnostics.didDelete(path)
        }.asJava
      } else if (isScalaOrJava && !savedFiles.isRecentlyActive(path)) {
        event.eventType() match {
          case EventType.CREATE =>
            buildTargets.onCreate(path)
          case _ =>
        }
        onChange(List(path)).asJava
      } else if (path.isSemanticdb) {
        Future {
          event.eventType() match {
            case EventType.DELETE =>
              semanticDBIndexer.onDelete(event.path())
            case EventType.CREATE | EventType.MODIFY =>
              semanticDBIndexer.onChange(event.path())
            case EventType.OVERFLOW =>
              semanticDBIndexer.onOverflow(event.path())
          }
        }.asJava
      } else if (path.isBuild) {
        onBuildChanged(List(path)).ignoreValue.asJava
      } else {
        CompletableFuture.completedFuture(())
      }
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
          compilations.compileFiles(paths),
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
      implementationProvider.implementations(position).asJava
    }

  @JsonRequest("textDocument/hover")
  def hover(params: TextDocumentPositionParams): CompletableFuture[Hover] =
    CancelTokens.future { token =>
      compilers
        .hover(params, token, interactiveSemanticdbs)
        .map(
          _.orElse {
            val path = params.getTextDocument.getUri.toAbsolutePath
            if (path.isWorksheet)
              worksheetProvider.hover(path, params.getPosition())
            else
              None
          }.orNull
        )
    }

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CancelTokens { _ => documentHighlightProvider.documentHighlight(params) }

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] =
    CancelTokens.future { _ =>
      compilers.documentSymbol(params).map { result =>
        if (initializeParams.supportsHierarchicalDocumentSymbols) {
          JEither.forLeft(result)
        } else {
          val infos = result.asScala
            .toSymbolInformation(params.getTextDocument.getUri)
            .asJava
          JEither.forRight(infos)
        }
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

  @JsonRequest("textDocument/onTypeFormatting")
  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token => compilers.onTypeFormatting(params) }

  @JsonRequest("textDocument/rangeFormatting")
  def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token => compilers.rangeFormatting(params) }

  @JsonRequest("textDocument/prepareRename")
  def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[l.Range] =
    CancelTokens.future { token =>
      renameProvider.prepareRename(params, token).map(_.orNull)
    }

  @JsonRequest("textDocument/rename")
  def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit] =
    CancelTokens.future { token => renameProvider.rename(params, token) }

  @JsonRequest("textDocument/references")
  def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens { _ => referencesResult(params).locations.asJava }

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
            statusBar
              .addMessage(clientConfig.initialConfig.icons.info + message)
          }
      }
    }
  }
  def referencesResult(params: ReferenceParams): ReferencesResult = {
    val timer = new Timer(time)
    val result = referencesProvider.references(params)
    if (clientConfig.initialConfig.statistics.isReferences) {
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
    CancelTokens.future { token => compilers.completions(params, token) }

  @JsonRequest("completionItem/resolve")
  def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    CancelTokens.future { token =>
      if (clientConfig.isCompletionItemResolve) {
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
  ): CompletableFuture[util.List[l.CodeAction]] =
    CancelTokens.future { token =>
      codeActionProvider.codeActions(params, token).map(_.asJava)
    }

  @JsonRequest("textDocument/codeLens")
  def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    CancelTokens { _ =>
      timedThunk("code lens generation", thresholdMillis = 1.second.toMillis) {
        val path = params.getTextDocument.getUri.toAbsolutePath
        codeLensProvider.findLenses(path).toList.asJava
      }
    }

  @JsonRequest("textDocument/foldingRange")
  def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] = {
    CancelTokens.future { token => compilers.foldingRange(params, token) }
  }

  @JsonRequest("workspace/symbol")
  def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    CancelTokens.future { token =>
      indexingPromise.future.map { _ =>
        val timer = new Timer(time)
        val result = workspaceSymbols.search(params.getQuery, token).asJava
        if (clientConfig.initialConfig.statistics.isWorkspaceSymbol) {
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
  def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] = {
    val command = Option(params.getCommand).getOrElse("")
    command.stripPrefix("metals.") match {
      case ServerCommands.ScanWorkspaceSources() =>
        Future {
          indexWorkspaceSources()
        }.asJavaObject
      case ServerCommands.RestartBuildServer() =>
        bloopServers.shutdownServer()
        autoConnectToBuildServer().asJavaObject
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
      case ServerCommands.CleanCompile() =>
        compilations.recompileAll().asJavaObject
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
          val linesCount = log.readText.linesIterator.size
          val pos = new l.Position(linesCount, 0)
          languageClient.metalsExecuteClientCommand(
            new ExecuteCommandParams(
              ClientCommands.GotoLocation.id,
              List(
                new Location(
                  log.toURI.toString(),
                  new l.Range(pos, pos)
                ): Object
              ).asJava
            )
          )
        }.asJavaObject
      case ServerCommands.StartDebugAdapter() =>
        val args = params.getArguments.asScala
        import DebugParametersJsonParsers._
        val debugSessionParams: Future[b.DebugSessionParams] = args match {
          case Seq(debugSessionParamsParser.Jsonized(params))
              if params.getData != null =>
            Future.successful(params)
          case Seq(mainClassParamsParser.Jsonized(params))
              if params.mainClass != null =>
            debugProvider.resolveMainClassParams(params)
          case Seq(testClassParamsParser.Jsonized(params))
              if params.testClass != null =>
            debugProvider.resolveTestClassParams(params)
          case _ =>
            val argExample = ServerCommands.StartDebugAdapter.arguments
            val msg = s"Invalid arguments: $args. Expecting: $argExample"
            Future.failed(new IllegalArgumentException(msg))
        }
        val session = for {
          params <- debugSessionParams
          server <- debugProvider.start(
            params
          )
        } yield {
          cancelables.add(server)
          DebugSession(server.sessionName, server.uri.toString)
        }
        session.asJavaObject

      case ServerCommands.GotoSuperMethod() =>
        Future {
          val command = supermethods.getGoToSuperMethodCommand(params)
          command.foreach(languageClient.metalsExecuteClientCommand)
          scribe.debug(s"Executing GoToSuperMethod ${command}")
        }.asJavaObject

      case ServerCommands.SuperMethodHierarchy() =>
        scribe.debug(s"Executing SuperMethodHierarchy ${command}")
        supermethods.jumpToSelectedSuperMethod(params).asJavaObject

      case ServerCommands.NewScalaFile() =>
        val args = params.getArguments.asScala
        val directoryURI = args.lift(0).collect {
          case directory: JsonPrimitive if directory.isString =>
            new URI(directory.getAsString())
        }
        val name = args.lift(1).collect {
          case name: JsonPrimitive if name.isString =>
            name.getAsString()
        }
        newFilesProvider.createNewFileDialog(directoryURI, name).asJavaObject
      case ServerCommands.StartAmmoniteBuildServer() =>
        ammonite.start().asJavaObject
      case ServerCommands.StopAmmoniteBuildServer() =>
        ammonite.stop()
      case ServerCommands.NewScalaProject() =>
        newProjectProvider.createNewProjectFromTemplate().asJavaObject
      case cmd =>
        scribe.error(s"Unknown command '$cmd'")
        Future.successful(()).asJavaObject
    }
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

  private def supportedBuildTool(): Future[Option[BuildTool]] = {
    def isCompatibleVersion(buildTool: BuildTool) = {
      val isCompatibleVersion = SemVer.isCompatibleVersion(
        buildTool.minimumVersion,
        buildTool.version
      )
      if (isCompatibleVersion) {
        Some(buildTool)
      } else {
        scribe.warn(s"Unsupported $buildTool version ${buildTool.version}")
        languageClient.showMessage(
          Messages.IncompatibleBuildToolVersion.params(buildTool)
        )
        None
      }
    }

    buildTools.loadSupported match {
      case Nil => {
        if (!buildTools.isAutoConnectable) {
          warnings.noBuildTool()
        }
        Future(None)
      }
      case buildTool :: Nil => Future(isCompatibleVersion(buildTool))
      case buildTools =>
        for {
          Some(buildTool) <- bloopInstall.checkForChosenBuildTool(buildTools)
        } yield isCompatibleVersion(buildTool)
    }
  }

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    for {
      possibleBuildTool <- supportedBuildTool
      buildChange <- possibleBuildTool match {
        case Some(buildTool) =>
          buildTool.digest(workspace) match {
            case None =>
              scribe.warn(s"Skipping build import, no checksum.")
              Future.successful(BuildChange.None)
            case Some(digest) =>
              slowConnectToBuildServer(forceImport, buildTool, digest)
          }
        case None =>
          Future.successful(BuildChange.None)
      }
    } yield buildChange
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
              Messages.ImportProjectPartiallyFailed
            )
            // Connect nevertheless, many build import failures are caused
            // by resolution errors in one weird module while other modules
            // exported successfully.
            quickConnectToBuildServer()
          } else {
            languageClient.showMessage(Messages.ImportProjectFailed)
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
    def compileAllOpenFiles: BuildChange => Future[BuildChange] = {
      case change if !change.isFailed =>
        Future
          .sequence[Unit, List](
            compilations
              .cascadeCompileFiles(buffers.open.toSeq)
              .ignoreValue ::
              compilers.load(buffers.open.toSeq) ::
              Nil
          )
          .map(_ => change)
      case other => Future.successful(other)
    }

    {
      for {
        _ <- disconnectOldBuildServer()
        maybeBuild <- timed("connected to build server") {
          if (buildTools.isBloop) bloopServers.newServer(userConfig)
          else bspServers.newServer()
        }
        result <- maybeBuild match {
          case Some(build) =>
            val result = connectToNewBuildServer(build)
            build.onReconnection { reconnected =>
              connectToNewBuildServer(reconnected)
                .flatMap(compileAllOpenFiles)
                .ignoreValue
            }
            result
          case None =>
            Future.successful(BuildChange.None)
        }
        _ = {
          treeView.init()
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
    }.flatMap(compileAllOpenFiles)
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
    scribe.info(s"Connected to Build server v${build.version}")
    cancelables.add(build)
    compilers.cancel()
    buildServer = Some(build)
    val importedBuild0 = timed("imported build") {
      MetalsLanguageServer.importedBuild(build)
    }
    for {
      i <- statusBar.trackFuture("Importing build", importedBuild0)
      _ = {
        lastImportedBuild = i
      }
      _ <- profiledIndexWorkspace(() => doctor.check(build.name, build.version))
      _ = checkRunningBloopVersion(build.version)
    } yield {
      BuildChange.Reconnected
    }
  }

  private def indexWorkspaceSources(): Unit = {
    for {
      (sourceItem, targets) <- buildTargets.sourceItemsToBuildTargets
      source <- sourceItem.listRecursive
      if source.isScalaOrJava
    } {
      targets.asScala.foreach { target =>
        buildTargets.linkSourceFile(target, source)
      }
      indexSourceFile(source, Some(sourceItem), targets.asScala.headOption)
    }
  }

  private def reindexWorkspaceSources(
      paths: Seq[AbsolutePath]
  ): Unit = {
    for {
      path <- paths.iterator
      if path.isScalaOrJava
    } {
      indexSourceFile(path, buildTargets.inverseSourceItem(path), None)
    }
  }

  private def sourceToIndex(
      source: AbsolutePath,
      targetOpt: Option[b.BuildTargetIdentifier]
  ): AbsolutePath =
    targetOpt
      .flatMap(ammonite.generatedScalaPath(_, source))
      .getOrElse(source)

  private def indexSourceFile(
      source: AbsolutePath,
      sourceItem: Option[AbsolutePath],
      targetOpt: Option[b.BuildTargetIdentifier]
  ): Unit = {

    try {
      val sourceToIndex0 = sourceToIndex(source, targetOpt)
      val reluri = source.toIdeallyRelativeURI(sourceItem)
      val input = sourceToIndex0.toInput
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
          if (
            sourceItem.isDefined &&
            !info.symbol.isPackage &&
            (owner.isPackage || source.isAmmoniteScript)
          ) {
            definitionIndex.addToplevelSymbol(reluri, source, info.symbol)
          }
      }
      workspaceSymbols.didChange(source, symbols)

      // Since the `symbols` here are toplevel symbols,
      // we cannot use `symbols` for expiring the cache for all symbols in the source.
      symbolDocs.expireSymbolDefinition(sourceToIndex0)
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

  def timedThunk[T](
      didWhat: String,
      onlyIf: Boolean = true,
      thresholdMillis: Long = 0
  )(thunk: => T): T = {
    val elapsed = new Timer(time)
    val result = thunk
    if (
      onlyIf && (thresholdMillis == 0 || elapsed.elapsedMillis > thresholdMillis)
    ) {
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
      if (reportStatus || elapsed.isLogWorthy) {
        scribe.info(s"time: $didWhat in $elapsed")
      }
      (elapsed, value)
    }
  }

  private def profiledIndexWorkspace(check: () => Unit): Future[Unit] = {
    val tracked = statusBar.trackFuture(
      s"Indexing",
      Future {
        timedThunk("indexed workspace", onlyIf = true) {
          try indexWorkspace(check)
          finally {
            indexingPromise.trySuccess(())
          }
        }
      }
    )
    tracked.foreach { _ =>
      statusBar.addMessage(
        s"${clientConfig.initialConfig.icons.rocket}Indexing complete!"
      )
      if (clientConfig.initialConfig.statistics.isMemory) {
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
        logMemory(
          "build targets",
          buildTargets
        )
      }
    }
    tracked
  }

  private def logMemory(name: String, index: Object): Unit = {
    val footprint = Memory.footprint(name, index)
    scribe.info(s"memory: $footprint")
  }

  private var lastImportedBuild = ImportedBuild.empty

  private def indexWorkspace(check: () => Unit): Unit = {
    val i = lastImportedBuild ++ ammonite.lastImportedBuild
    timedThunk(
      "updated build targets",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      buildTargets.reset()
      interactiveSemanticdbs.reset()
      buildClient.reset()
      semanticDBIndexer.reset()
      treeView.reset()
      worksheetProvider.reset()
      symbolSearch.reset()
      buildTargets.addWorkspaceBuildTargets(i.workspaceBuildTargets)
      buildTargets.addScalacOptions(i.scalacOptions)
      for {
        item <- i.sources.getItems.asScala
        source <- item.getSources.asScala
      } {
        val sourceItemPath = source.getUri.toAbsolutePath(followSymlink = false)
        buildTargets.addSourceItem(sourceItemPath, item.getTarget)
      }
      check()
      buildTools
        .loadSupported()
        .foreach(_.onBuildTargets(workspace, buildTargets))
    }
    timedThunk(
      "started file watcher",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      try {
        fileWatcher.restart()
      } catch {
        // note(@tgodzik) This is needed in case of ammonite
        // where it can rarely deletes directories while we are trying to watch them
        case NonFatal(e) =>
          scribe.warn("File watching failed, indexes will not be updated.", e)
      }
    }
    timedThunk(
      "indexed library classpath",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      workspaceSymbols.indexClasspath()
    }
    timedThunk(
      "indexed workspace SemanticDBs",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      semanticDBIndexer.onScalacOptions(i.scalacOptions)
    }
    timedThunk(
      "indexed workspace sources",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      indexWorkspaceSources()
    }
    timedThunk(
      "indexed library sources",
      clientConfig.initialConfig.statistics.isIndex
    ) {
      indexDependencySources(i.dependencySources)
    }

    focusedDocument.foreach { doc =>
      buildTargets
        .inverseSources(doc)
        .foreach(focusedDocumentBuildTarget.set)
    }

    val targets = buildTargets.all.map(_.id).toSeq
    buildTargetClasses
      .rebuildIndex(targets)
      .foreach(_ => languageClient.refreshModel(buildChanged = true))
  }

  private def checkRunningBloopVersion(bspServerVersion: String) = {
    if (doctor.isUnsupportedBloopVersion(bspServerVersion)) {
      val notification = tables.dismissedNotifications.IncompatibleBloop
      if (!notification.isDismissed) {
        val messageParams = IncompatibleBloopVersion.params(
          bspServerVersion,
          BuildInfo.bloopVersion,
          isChangedInSettings = userConfig.bloopVersion != None
        )
        languageClient.showMessageRequest(messageParams).asScala.foreach {
          case action if action == IncompatibleBloopVersion.shutdown =>
            bloopServers.shutdownServer()
            autoConnectToBuildServer()
          case action if action == IncompatibleBloopVersion.dismissForever =>
            notification.dismissForever()
          case _ =>
        }
      }
    }
  }

  private def indexDependencySources(
      dependencySources: b.DependencySourcesResult
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
    val isVisited = new ju.HashSet[String]()
    for {
      item <- dependencySources.getItems.asScala
      sourceUri <- Option(item.getSources).toList.flatMap(_.asScala)
      if !isVisited.contains(sourceUri)
    } {
      isVisited.add(sourceUri)
      val path = sourceUri.toAbsolutePath
      try {
        buildTargets.addDependencySource(path, item.getTarget)
        if (path.isJar) {
          usedJars += path
          addSourceJarSymbols(path)
        } else if (path.isDirectory) {
          definitionIndex.addSourceDirectory(path)
        } else {
          scribe.warn(s"unexpected dependency: $path")
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
            val tempIndex = OnDemandSymbolIndex(onError = {
              case e: InvalidJarException =>
                scribe.warn(s"invalid jar: ${e.path}")
              case NonFatal(e) =>
                scribe.debug(s"jar error: $path", e)
            })
            tempIndex.addSourceJar(path)
            if (tempIndex.toplevels.nonEmpty) {
              tables.jarSymbols.putTopLevels(path, tempIndex.toplevels)
            }
            tempIndex.toplevels
        }
      }
    )
  }

  private def onWorksheetChanged(
      paths: Seq[AbsolutePath]
  ): Future[Unit] = {
    paths
      .find { path =>
        if (clientConfig.isDidFocusProvider || focusedDocument.isDefined) {
          focusedDocument.contains(path) &&
          path.isWorksheet
        } else {
          path.isWorksheet
        }
      }
      .fold(Future.successful(()))(
        worksheetProvider.evaluateAndPublish(_, EmptyCancelToken)
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
      positionParams: TextDocumentPositionParams,
      token: CancelToken = EmptyCancelToken,
      definitionOnly: Boolean = false
  ): Future[DefinitionResult] = {
    val source = positionParams.getTextDocument.getUri.toAbsolutePath
    if (source.isScalaFilename) {
      val semanticDBDoc =
        semanticdbs.textDocument(source).documentIncludingStale
      (for {
        doc <- semanticDBDoc
        positionOccurrence = definitionProvider.positionOccurrence(
          source,
          positionParams.getPosition,
          doc
        )
        occ <- positionOccurrence.occurrence
      } yield occ) match {
        case Some(occ) =>
          if (occ.role.isDefinition && !definitionOnly) {
            val refParams = new ReferenceParams(
              positionParams.getTextDocument(),
              positionParams.getPosition(),
              new ReferenceContext(false)
            )
            val result = referencesResult(refParams)
            if (result.locations.isEmpty) {
              // Fallback again to the original behavior that returns
              // the definition location itself if no reference locations found,
              // for avoiding the confusing messages like "No definition found ..."
              definitionResult(positionParams, token)
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
            definitionResult(positionParams, token)
          }
        case None =>
          if (semanticDBDoc.isEmpty) {
            warnings.noSemanticdb(source)
          }
          // Even if it failed to retrieve the symbol occurrence from semanticdb,
          // try to find its definitions from presentation compiler.
          definitionResult(positionParams, token)
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
    if (source.isScalaFilename) {
      val result =
        timedThunk(
          "definition",
          clientConfig.initialConfig.statistics.isDefinition
        )(
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
  ): Future[util.List[DocumentSymbol]] = {
    compilers.documentSymbol(params)
  }

  private def newSymbolIndex(): OnDemandSymbolIndex = {
    OnDemandSymbolIndex(
      onError = {
        case e @ (_: ParseException | _: TokenizeException) =>
          scribe.error(e.toString)
        case NonFatal(e) =>
          scribe.error("unexpected error during source scanning", e)
      },
      toIndexSource = path => {
        if (path.isAmmoniteScript)
          for {
            target <- buildTargets.sourceBuildTargets(path).headOption
            toIndex <- ammonite.generatedScalaPath(target, path)
          } yield toIndex
        else
          None
      }
    )
  }

}

object MetalsLanguageServer {

  def importedBuild(
      build: BuildServerConnection
  )(implicit ec: ExecutionContext): Future[ImportedBuild] =
    for {
      workspaceBuildTargets <- build.workspaceBuildTargets()
      ids = workspaceBuildTargets.getTargets.map(_.getId)
      scalacOptions <-
        build
          .buildTargetScalacOptions(new b.ScalacOptionsParams(ids))
      sources <-
        build
          .buildTargetSources(new b.SourcesParams(ids))
      dependencySources <-
        build
          .buildTargetDependencySources(new b.DependencySourcesParams(ids))
    } yield {
      ImportedBuild(
        workspaceBuildTargets,
        scalacOptions,
        sources,
        dependencySources
      )
    }
}
