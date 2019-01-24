package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.SourcesParams
import com.google.gson.JsonElement
import io.methvin.watcher.DirectoryChangeEvent
import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.undertow.server.HttpServerExchange
import java.net.URI
import java.nio.charset.Charset
import scala.meta.internal.semanticdb.Scala._
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTool.Sbt
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.ListFiles
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.tokenizers.TokenizeException
import scala.util.Try
import scala.util.control.NonFatal

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
    sh: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) extends Cancelable {

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
  private val definitionIndex = newSymbolIndex()
  var buildServer = Option.empty[BuildServerConnection]
  private val openTextDocument = new AtomicReference[AbsolutePath]()
  private val savedFiles = new ActiveFiles(time)
  private val openedFiles = new ActiveFiles(time)
  private val messages = new Messages(config.icons)
  private val languageClient =
    new DelegatingLanguageClient(NoopLanguageClient, config)
  var userConfig = UserConfiguration()
  val buildTargets: BuildTargets = new BuildTargets()
  private val fileWatcher = register(
    new FileWatcher(
      buildTargets,
      params => didChangeWatchedFiles(params)
    )
  )
  private val indexingPromise = Promise[Unit]()

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
  private var definitionProvider: DefinitionProvider = _
  private var formattingProvider: FormattingProvider = _
  private var initializeParams: Option[InitializeParams] = None
  private var referencesProvider: ReferenceProvider = _
  private var workspaceSymbols: WorkspaceSymbolProvider = _
  var tables: Tables = _
  var statusBar: StatusBar = _
  private var embedded: Embedded = _
  private var doctor: Doctor = _
  var httpServer: Option[MetalsHttpServer] = None

  def connectToLanguageClient(client: MetalsLanguageClient): Unit = {
    languageClient.underlying = client
    statusBar = new StatusBar(() => languageClient, time, progressTicks)
    embedded = register(new Embedded(config.icons, statusBar, () => userConfig))
    LanguageClientLogger.languageClient = Some(languageClient)
    cancelables.add(() => languageClient.shutdown())
  }

  def register[T <: Cancelable](cancelable: T): T = {
    cancelables.add(cancelable)
    cancelable
  }

  private def updateWorkspaceDirectory(params: InitializeParams): Unit = {
    workspace = AbsolutePath(Paths.get(URI.create(params.getRootUri)))
    MetalsLogger.setupLspLogger(workspace, redirectSystemOut)
    tables = register(new Tables(workspace, time, config))
    buildTools = new BuildTools(workspace, bspGlobalDirectories)
    fileSystemSemanticdbs =
      new FileSystemSemanticdbs(buildTargets, charset, workspace, fingerprints)
    interactiveSemanticdbs = register(
      new InteractiveSemanticdbs(
        workspace,
        buildTargets,
        charset,
        languageClient,
        tables,
        messages,
        statusBar
      )
    )
    warnings = new Warnings(
      workspace,
      buildTargets,
      statusBar,
      config.icons,
      buildTools,
      isCompiling.contains
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
      time
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
    definitionProvider = new DefinitionProvider(
      workspace,
      mtags,
      buffers,
      definitionIndex,
      semanticdbs,
      config.icons,
      statusBar,
      warnings
    )
    formattingProvider = new FormattingProvider(
      workspace,
      buffers,
      embedded,
      config,
      () => userConfig,
      languageClient,
      statusBar,
      config.icons
    )
    referencesProvider = new ReferenceProvider(
      workspace,
      semanticdbs,
      buffers,
      definitionProvider
    )
    workspaceSymbols = new WorkspaceSymbolProvider(
      workspace,
      config.statistics,
      buildTargets,
      definitionIndex,
      pkg => referencesProvider.referencedPackages.mightContain(pkg),
      interactiveSemanticdbs.toFileOnDisk
    )
    doctor = new Doctor(
      workspace,
      buildTargets,
      config,
      languageClient,
      () => httpServer,
      tables
    )
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
      warnUnsupportedJavaVersion()
      initializeParams = Option(params)
      updateWorkspaceDirectory(params)
      val capabilities = new ServerCapabilities()
      capabilities.setExecuteCommandProvider(
        new ExecuteCommandOptions(
          ServerCommands.all.map(_.id).asJava
        )
      )
      capabilities.setDefinitionProvider(true)
      capabilities.setReferencesProvider(true)
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
      new InitializeResult(capabilities)
    }).asJava
  }

  def isUnsupportedJavaVersion: Boolean =
    scala.util.Properties.isJavaAtLeast("9")
  def warnUnsupportedJavaVersion(): Unit = {
    if (isUnsupportedJavaVersion) {
      val javaVersion = System.getProperty("java.version")
      val message =
        s"Unsupported Java version $javaVersion, no functionality will work. " +
          s"To fix this problem, restart the server using Java 8."
      languageClient.showMessage(new MessageParams(MessageType.Error, message))
      scribe.error(message)
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
        sh
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
            Future(startHttpServer())
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
    shutdownPromise.get().future.onComplete { _ =>
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
      CompletableFutures.computeAsync { _ =>
        // trigger compilation in preparation for definition requests
        interactiveSemanticdbs.textDocument(path)
        // publish diagnostics
        interactiveSemanticdbs.didFocus(path)
        ()
      }
    } else {
      compileSourceFiles(List(path)).asJava
    }
  }

  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(uri: String): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
    // Don't trigger compilation on didFocus events under cascade compilation
    // because save events already trigger compile in inverse dependencies.
    if (openedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(DidFocusResult.RecentlyActive)
    } else {
      buildTargets.inverseSources(path) match {
        case Some(target) =>
          val needsCompile = !lastCompile(target) &&
            buildTargets.isInverseDependency(target, lastCompile.toList)
          if (needsCompile) {
            compileSourceFiles(List(path))
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

  @JsonNotification("textDocument/didChange")
  def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = {
    CompletableFuture.completedFuture {
      params.getContentChanges.asScala.headOption.foreach { change =>
        val path = params.getTextDocument.getUri.toAbsolutePath
        buffers.put(path, change.getText)
        trees.didChange(path)
        diagnostics.didChange(path)
      }
    }
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
    trees.didChange(path)
    onChange(List(path))
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(params: DidChangeConfigurationParams): Unit = {
    val json = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
    UserConfiguration.fromJson(json) match {
      case Left(errors) =>
        errors.foreach { error =>
          scribe.error(s"config error: $error")
        }
      case Right(value) =>
        userConfig = value
    }
  }

  @JsonNotification("workspace/didChangeWatchedFiles")
  def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] = {
    val paths = params.getChanges.asScala.iterator
      .map(_.getUri.toAbsolutePath)
      .filterNot(savedFiles.isRecentlyActive) // de-duplicate didSave events.
      .toSeq
    onChange(paths)
  }

  def didChangeWatchedFiles(
      event: DirectoryChangeEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path())
    if (!savedFiles.isRecentlyActive(path) && path.isScalaOrJava) {
      onChange(List(path))
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

  private def onChange(paths: Seq[AbsolutePath]): CompletableFuture[Unit] = {
    paths.foreach { path =>
      fingerprints.add(path, FileIO.slurp(path, charset))
    }
    Future
      .sequence(
        List(
          Future(reindexWorkspaceSources(paths)),
          compileSourceFiles(paths).ignoreValue,
          onSbtBuildChanged(paths).ignoreValue
        )
      )
      .ignoreValue
      .asJava
  }

  @JsonRequest("textDocument/definition")
  def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { _ =>
      definitionResult(position).locations
    }

  @JsonRequest("textDocument/typeDefinition")
  def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/typeDefinition is not supported.")
      null
    }

  @JsonRequest("textDocument/implementation")
  def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/implementation is not supported.")
      null
    }

  @JsonRequest("textDocument/hover")
  def hover(params: TextDocumentPositionParams): CompletableFuture[Hover] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/hover is not supported.")
      null
    }

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[Hover] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/documentHighlight is not supported.")
      null
    }

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] =
    CompletableFutures.computeAsync { _ =>
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

  private def cancelableFuture[T](fn: CancelChecker => Future[T]): Future[T] = {
    CompletableFutures.computeAsync(t => t).asScala.flatMap(fn)
  }
  private def cancelableJavaFuture[T](
      fn: CancelChecker => Future[T]
  ): CompletableFuture[T] = {
    cancelableFuture(fn).asJava
  }

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    cancelableJavaFuture { token =>
      formattingProvider.format(
        params.getTextDocument.getUri.toAbsolutePath,
        token
      )
    }

  @JsonRequest("textDocument/rename")
  def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/rename is not supported.")
      null
    }

  @JsonRequest("textDocument/references")
  def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { _ =>
      val timer = new Timer(time)
      val result = referencesResult(params)
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
        compileAndLookForNewReferfences(params, result)
      }
      result.locations.asJava
    }

  // Triggers a cascade compilation and tries to find new references to a given symbol.
  // It's not possible to stream reference results so if we find new symbols we notify the
  // user to run references again to see updated results.
  private def compileAndLookForNewReferfences(
      params: ReferenceParams,
      result: ReferencesResult
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val old = path.toInputFromBuffers(buffers)
    cascadeCompileSourceFiles(Seq(path)).foreach { _ =>
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
          val newResult = referencesResult(p)
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
  def referencesResult(params: ReferenceParams): ReferencesResult =
    referencesProvider.references(params)

  @JsonRequest("textDocument/completion")
  def completion(params: CompletionParams): CompletableFuture[CompletionList] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/completion is not supported.")
      null
    }

  @JsonRequest("textDocument/signatureHelp")
  def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/signatureHelp is not supported.")
      null
    }

  @JsonRequest("textDocument/codeAction")
  def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[CodeAction]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/codeAction is not supported.")
      null
    }

  @JsonRequest("textDocument/codeLens")
  def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/codeLens is not supported.")
      null
    }

  @JsonRequest("workspace/symbol")
  def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[util.List[SymbolInformation]] =
    cancelableJavaFuture { token =>
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
        cascadeCompileSourceFiles(buffers.open.toSeq).asJavaObject
      case ServerCommands.CancelCompile() =>
        Future {
          compileSourceFiles.cancelCurrentRequest()
          cascadeCompileSourceFiles.cancelCurrentRequest()
          scribe.info("compilation cancelled")
        }.asJavaObject
      case els =>
        scribe.error(s"Unknown command '$els'")
        Future.successful(()).asJavaObject
    }

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    buildTools.asSbt match {
      case None =>
        if (!buildTools.isAutoConnectable) {
          warnings.noBuildTool()
        }
        Future.successful(BuildChange.None)
      case Some(sbt) =>
        if (!isCompatibleSbtVersion(sbt.version)) {
          scribe.warn(
            s"Skipping build import for unsupported sbt version ${sbt.version}"
          )
          languageClient.showMessage(
            messages.IncompatibleSbtVersion.params(sbt)
          )
          Future.successful(BuildChange.None)
        } else {
          SbtDigest.current(workspace) match {
            case None =>
              scribe.warn(s"Skipping build import, no checksum.")
              Future.successful(BuildChange.None)
            case Some(digest) =>
              slowConnectToBuildServer(forceImport, sbt, digest)
          }
        }
    }
  }

  private def isCompatibleSbtVersion(version: String): Boolean = {
    version.split('.') match {
      case Array("1", _, _) => true
      case Array("0", "13", patch)
          if Try(patch.toInt).filter(_ >= 17).isSuccess =>
        true
      case _ => false
    }
  }

  private def slowConnectToBuildServer(
      forceImport: Boolean,
      sbt: Sbt,
      checksum: String
  ): Future[BuildChange] =
    for {
      result <- {
        if (forceImport) bloopInstall.runUnconditionally(sbt)
        else bloopInstall.runIfApproved(sbt, checksum)
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
    } else if (isUnsupportedJavaVersion) {
      Future.successful(BuildChange.None)
    } else {
      autoConnectToBuildServer()
    }
  }

  private def autoConnectToBuildServer(): Future[BuildChange] = {
    for {
      _ <- buildServer match {
        case Some(old) => old.shutdown()
        case None => Future.successful(())
      }
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
    } yield result
  }.recover {
    case NonFatal(e) =>
      val message =
        "Failed to connect with build server, no functionality will work."
      val details = " See logs for more details."
      buildServer.foreach(_.shutdown())
      buildServer = None
      scribe.error(message, e)
      languageClient.showMessage(
        new MessageParams(MessageType.Error, message + details)
      )
      BuildChange.Failed
  }

  private def connectToNewBuildServer(
      build: BuildServerConnection
  ): Future[BuildChange] = {
    cancelables.add(build)
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
      _ <- cascadeCompileSourceFiles(buffers.open.toSeq)
    } yield {
      BuildChange.Reconnected
    }
  }

  private def indexWorkspaceSources(): Unit = {
    for {
      sourceDirectory <- buildTargets.sourceDirectories
      if sourceDirectory.isDirectory
      source <- ListFiles(sourceDirectory)
      if source.isScalaOrJava
    } {
      indexSourceFile(source, Some(sourceDirectory))
    }
  }

  private def reindexWorkspaceSources(
      paths: Seq[AbsolutePath]
  ): Unit = {
    for {
      path <- paths
      if path.isScalaOrJava
    } {
      indexSourceFile(path, buildTargets.inverseSourceDirectory(path))
    }
  }

  private def indexSourceFile(
      source: AbsolutePath,
      sourceDirectory: Option[AbsolutePath]
  ): Unit = {
    try {
      val reluri = source.toIdeallyRelativeURI(sourceDirectory)
      val input = source.toInput
      val symbols = ArrayBuffer.empty[String]
      SemanticdbDefinition.foreach(input) {
        case SemanticdbDefinition(info, _, owner) =>
          if (WorkspaceSymbolProvider.isRelevantKind(info.kind)) {
            symbols += info.symbol
          }
          if (sourceDirectory.isDefined &&
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

  def timedThunk[T](didWhat: String, onlyIf: Boolean)(thunk: => T): T = {
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
          workspaceSymbols.inDependencies
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
      buildTargets.addWorkspaceBuildTargets(i.workspaceBuildTargets)
      buildTargets.addScalacOptions(i.scalacOptions)
      for {
        item <- i.sources.getItems.asScala
        source <- item.getSources.asScala
        if source.getUri.endsWith("/")
      } {
        val directory = source.getUri.toAbsolutePath
        buildTargets.addSourceDirectory(directory, item.getTarget)
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
    JdkSources(userConfig.javaHome).foreach { zip =>
      definitionIndex.addSourceJar(zip)
    }
    for {
      item <- dependencySources.getItems.asScala
      sourceUri <- Option(item.getSources).toList.flatMap(_.asScala)
    } {
      try {
        val path = sourceUri.toAbsolutePath
        buildTargets.addDependencySource(path, item.getTarget)
        if (path.isJar) {
          definitionIndex.addSourceJar(path)
        } else {
          scribe.warn(s"unexpected dependency directory: $path")
        }
      } catch {
        case NonFatal(e) =>
          scribe.error(s"error processing $sourceUri", e)
      }
    }
  }

  private val isCompiling = TrieMap.empty[BuildTargetIdentifier, Boolean]
  private var lastCompile: collection.Set[BuildTargetIdentifier] = Set.empty
  val cascadeCompileSourceFiles =
    new BatchedFunction[AbsolutePath, Unit](
      paths => compileSourceFilesUnbatched(paths, isCascade = true)
    )
  val compileSourceFiles =
    new BatchedFunction[AbsolutePath, Unit](
      paths => compileSourceFilesUnbatched(paths, isCascade = false)
    )
  private def compileSourceFilesUnbatched(
      paths: Seq[AbsolutePath],
      isCascade: Boolean
  ): CancelableFuture[Unit] = {
    val scalaPaths = paths.filter { path =>
      path.isScalaOrJava &&
      !path.isDependencySource(workspace)
    }
    buildServer match {
      case Some(build) if scalaPaths.nonEmpty =>
        val targets = scalaPaths.flatMap(buildTargets.inverseSources).distinct
        if (targets.isEmpty) {
          scribe.warn(s"no build target: ${scalaPaths.mkString("\n  ")}")
          Future.successful(()).asCancelable
        } else {
          val allTargets =
            if (isCascade) {
              targets.flatMap(buildTargets.inverseDependencies).distinct
            } else {
              targets
            }
          val params = new CompileParams(allTargets.asJava)
          targets.foreach(target => isCompiling(target) = true)
          val completableFuture = build.compile(params)
          CancelableFuture(
            completableFuture.asScala.map { _ =>
              lastCompile = isCompiling.keySet
              isCompiling.clear()
            }.ignoreValue,
            Cancelable(() => completableFuture.cancel(true))
          )
        }
      case _ =>
        Future.successful(()).asCancelable
    }
  }

  /**
   * Re-imports the sbt build if build files have changed.
   */
  private val onSbtBuildChanged =
    BatchedFunction.fromFuture[AbsolutePath, BuildChange](
      onSbtBuildChangedUnbatched
    )
  private def onSbtBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[BuildChange] = {
    val isBuildChange = paths.exists(_.isSbtRelated(workspace))
    if (isBuildChange) {
      slowConnectToBuildServer(forceImport = false)
    } else {
      Future.successful(BuildChange.None)
    }
  }

  /**
   * Returns textDocument/definition in addition to the resolved symbol.
   *
   * The resolved symbol is used for testing purposes only.
   */
  def definitionResult(
      position: TextDocumentPositionParams
  ): DefinitionResult = {
    val source = position.getTextDocument.getUri.toAbsolutePath
    if (source.toLanguage.isScala) {
      val result = timedThunk("definition", config.statistics.isDefinition)(
        definitionProvider.definition(source, position)
      )
      // Record what build target this dependency source (if any) was jumped from,
      // needed to know what classpath to compile the dependency source with.
      interactiveSemanticdbs.didDefinition(source, result)
      result
    } else {
      // Ignore non-scala files.
      DefinitionResult.empty
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
