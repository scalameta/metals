package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.StatusCode
import com.google.gson.JsonElement
import io.undertow.server.HttpServerExchange
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTool.Sbt
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.semanticdb.Language
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.util.control.NonFatal

class MetalsLanguageServer(
    ec: ExecutionContextExecutorService,
    buffers: Buffers = Buffers(),
    redirectSystemOut: Boolean = true,
    charset: Charset = StandardCharsets.UTF_8,
    time: Time = Time.system,
    config: MetalsServerConfig = MetalsServerConfig.default,
    progressTicks: ProgressTicks = ProgressTicks.braille
) extends Cancelable {

  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = cancelables.cancel()

  private implicit val executionContext: ExecutionContextExecutorService = ec
  private val sh = Executors.newSingleThreadScheduledExecutor()
  private val fingerprints = new MutableMd5Fingerprints
  private val mtags = new Mtags
  var workspace: AbsolutePath = _
  private val index = newSymbolIndex()
  var buildServer = Option.empty[BuildServerConnection]
  private val openTextDocument = new AtomicReference[AbsolutePath]()
  private val savedFiles = new ActiveFiles(time)
  private val openedFiles = new ActiveFiles(time)
  private val messages = new Messages(config.icons)
  private val languageClient =
    new DelegatingLanguageClient(NoopLanguageClient, config)
  private var userConfig = UserConfiguration()

  // These can't be instantiated until we know the workspace root directory.
  private var bloopInstall: BloopInstall = _
  private var diagnostics: Diagnostics = _
  private var buildTargets: BuildTargets = _
  private var fileSystemSemanticdbs: FileSystemSemanticdbs = _
  private var interactiveSemanticdbs: InteractiveSemanticdbs = _
  private var buildTools: BuildTools = _
  private var semanticdbs: Semanticdbs = _
  private var buildClient: MetalsBuildClient = _
  private var bloopServers: BloopServers = _
  private var definitionProvider: DefinitionProvider = _
  private var initializeParams: Option[InitializeParams] = None
  var tables: Tables = _
  private var statusBar: StatusBar = _
  private var embedded: Embedded = _
  private var fileEvents: Option[FileEvents] = None
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
    tables = register(Tables.forWorkspace(workspace, time))
    buildTools = new BuildTools(workspace)
    buildTargets = new BuildTargets()
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
    diagnostics = new Diagnostics(buildTargets, languageClient)
    buildClient = new ForwardingMetalsBuildClient(languageClient, diagnostics)
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
      index,
      semanticdbs,
      config.icons,
      statusBar
    )
    doctor = new Doctor(
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

  def fileWatcherGlobs: DidChangeWatchedFilesRegistrationOptions =
    new DidChangeWatchedFilesRegistrationOptions(
      List(
        new FileSystemWatcher("**/*.{scala,sbt,java}"),
        new FileSystemWatcher("**/project/build.properties")
      ).asJava
    )

  private def registerFileWatchers(): Unit = {
    val registration = for {
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
              fileWatcherGlobs
            )
          ).asJava
        )
      )
    }
    if (registration.isEmpty) {
      if (config.fileWatcher.isCustom) {
        () // Do nothing, client has custom file watcher.
      } else if (config.fileWatcher.isAuto) {
        scribe.info("Starting Metals file watcher...")
        val watcher = new FileEvents(
          workspace,
          fileWatcherGlobs,
          buildTargets,
          params => didChangeWatchedFiles(params)
        )
        fileEvents = Some(register(watcher))
      } else {
        scribe.warn(
          s"File watching is disabled, expect partial functionality. To fix this warning, either pass the " +
            s"system property -Dmetals.file-watcher=auto during startup or use an editor with file watching support."
        )
      }
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
      tables.start()
      registerFileWatchers()
      Future
        .sequence(
          List[Future[Unit]](
            quickConnectToBuildServer().ignoreValue,
            slowConnectToBuildServer(forceImport = false).ignoreValue,
            Future(startHttpServer())
          )
        )
        .ignoreValue
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
        cancelables.cancel()
      } catch {
        case NonFatal(e) =>
          scribe.error("cancellation error", e)
      } finally {
        promise.success(())
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
  def didFocus(uri: String): CompletableFuture[Unit] = {
    val path = uri.toAbsolutePath
    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
    if (openedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(())
    } else {
      compileSourceFiles(List(path)).asJava
    }
  }

  @JsonNotification("textDocument/didChange")
  def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = {
    CompletableFuture.completedFuture {
      params.getContentChanges.asScala.headOption.foreach { change =>
        buffers.put(
          params.getTextDocument.getUri.toAbsolutePath,
          change.getText
        )
      }
    }
  }

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    buffers.remove(path)
  }

  @JsonNotification("textDocument/didSave")
  def didSave(params: DidSaveTextDocumentParams): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    savedFiles.add(path)
    buffers.put(path, path.toInput.text)
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

  private def onChange(paths: Seq[AbsolutePath]): CompletableFuture[Unit] = {
    paths.foreach { path =>
      fingerprints.add(path, FileIO.slurp(path, charset))
    }
    Future
      .sequence(
        List(
          reindexSources(paths),
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
  ): CompletableFuture[util.List[DocumentSymbol]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/documentSymbol is not supported.")
      null
    }

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/formatting is not supported.")
      null
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
      position: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    CompletableFutures.computeAsync { _ =>
      scribe.warn("textDocument/references is not supported.")
      null
    }

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

  @JsonRequest("workspace/executeCommand")
  def executeCommand(params: ExecuteCommandParams): CompletableFuture[Object] =
    params.getCommand match {
      case ServerCommands.ScanWorkspaceSources() =>
        Future {
          buildTargets.sourceDirectories.foreach(indexSourceDirectory)
        }.asJavaObject
      case ServerCommands.ImportBuild() =>
        slowConnectToBuildServer(forceImport = true).asJavaObject
      case ServerCommands.ConnectBuildServer() =>
        quickConnectToBuildServer().asJavaObject
      case ServerCommands.RunDoctor() =>
        Future {
          doctor.executeRunDoctor()
        }.asJavaObject
      case ServerCommands.OpenBrowser(url) =>
        Future.successful(Urls.openBrowser(url)).asJavaObject
      case els =>
        scribe.error(s"Unknown command '$els'")
        Future.successful(()).asJavaObject
    }

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    buildTools.asSbt match {
      case None =>
        if (!buildTools.isBloop) {
          scribe.warn(
            s"Skipping build import for unsupport build tool $buildTools"
          )
        }
        Future.successful(BuildChange.None)
      case Some(sbt) =>
        SbtDigest.current(workspace) match {
          case None =>
            scribe.warn(s"Skipping build import, no checksum.")
            Future.successful(BuildChange.None)
          case Some(digest) =>
            slowConnectToBuildServer(forceImport, sbt, digest)
        }
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
          if (buildTools.isBloop) {
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
    if (!buildTools.isBloop) {
      scribe.warn("Unable to automatically connect to build server.")
      Future.successful(BuildChange.None)
    } else if (isUnsupportedJavaVersion) {
      Future.successful(BuildChange.None)
    } else {
      val importingBuild = for {
        _ <- buildServer match {
          case Some(old) => old.shutdown()
          case None => Future.successful(())
        }
        build <- timed("connected to build server")(bloopServers.newServer())
        _ = {
          cancelables.add(build)
          buildServer = Some(build)
        }
        _ <- installWorkspaceBuildTargets(build)
      } yield ()

      for {
        _ <- statusBar.trackFuture("Importing build", importingBuild)
        _ = {
          statusBar.addMessage(s"${config.icons.rocket}Imported build!")
          if (config.statistics.isMemory) {
            scribe.info(s"memory: ${Memory.footprint(index)}")
          }
        }
        _ <- compileSourceFiles(buffers.open.toSeq)
      } yield BuildChange.Reconnected
    }
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

  /**
   * Visit every file and directory in the workspace and register
   * toplevel definitions for scala source files.
   */
  private def indexSourceDirectory(
      sourceDirectory: AbsolutePath
  ): Future[Unit] = Future {
    if (sourceDirectory.isDirectory) {
      Files.walkFileTree(
        sourceDirectory.toNIO,
        new SimpleFileVisitor[Path] {
          override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
          ): FileVisitResult = {
            val path = AbsolutePath(file)
            path.toLanguage match {
              case Language.SCALA | Language.JAVA =>
                index.addSourceFile(path, Some(sourceDirectory))
              case _ =>
            }
            super.visitFile(file, attrs)
          }
        }
      )
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

  /**
   * Index all build targets in the workspace.
   */
  private def installWorkspaceBuildTargets(
      build: BuildServerConnection
  ): Future[Unit] = timed("imported workspace") {
    for {
      workspaceBuildTargets <- build.server.workspaceBuildTargets().asScala
      _ = {
        buildTargets.reset()
        interactiveSemanticdbs.reset()
        buildTargets.addWorkspaceBuildTargets(workspaceBuildTargets)
      }
      ids = workspaceBuildTargets.getTargets.map(_.getId)
      scalacOptions <- build.server
        .buildTargetScalacOptions(new ScalacOptionsParams(ids))
        .asScala
      _ = {
        buildTargets.addScalacOptions(scalacOptions)
        JdkSources(userConfig.javaHome).foreach { zip =>
          index.addSourceJar(zip)
        }
        doctor.check()
      }
      _ <- registerSourceDirectories(build, ids)
      dependencySources <- build.server
        .buildTargetDependencySources(new DependencySourcesParams(ids))
        .asScala
    } yield {
      for {
        item <- dependencySources.getItems.asScala
        sourceUri <- Option(item.getSources).toList.flatMap(_.asScala)
      } {
        try {
          val path = sourceUri.toAbsolutePath
          if (path.isJar) {
            // NOTE(olafur): here we rely on an implementation detail of the bloop BSP server,
            // once we upgrade to BSP v2 we can use buildTarget/sources instead of
            // buildTarget/dependencySources.
            index.addSourceJar(path)
          } else {
            scribe.warn(s"unexpected dependency directory: $path")
          }
        } catch {
          case NonFatal(e) =>
            scribe.error(s"error processing $sourceUri", e)
        }
      }
      fileEvents.foreach(_.start())
    }
  }

  private def registerSourceDirectories(
      build: BuildServerConnection,
      ids: util.List[BuildTargetIdentifier]
  ): Future[Unit] =
    for {
      sources <- build.server.buildTargetSources(new SourcesParams(ids)).asScala
    } yield {
      for {
        item <- sources.getItems.asScala
        source <- item.getSources.asScala
        if source.getUri.endsWith("/")
      } {
        val directory = source.getUri.toAbsolutePath
        buildTargets.addSourceDirectory(directory, item.getTarget)
        indexSourceDirectory(directory)
      }
    }

  private def reindexSources(
      paths: Seq[AbsolutePath]
  ): Future[Unit] = Future {
    for {
      path <- paths
      if path.isScalaOrJava
      dir <- buildTargets.sourceDirectories
      if path.toNIO.startsWith(dir.toNIO)
    } {
      index.addSourceFile(path, Some(dir))
    }
  }
  private val compileSourceFiles =
    new BatchedFunction[AbsolutePath, Unit](compileSourceFilesUnbatched)
  private def compileSourceFilesUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[Unit] = {
    val scalaPaths = paths.filter(_.isScalaOrJava)
    buildServer match {
      case Some(build) if scalaPaths.nonEmpty =>
        val targets = scalaPaths.flatMap(buildTargets.inverseSources).distinct
        if (targets.isEmpty) {
          scribe.warn(s"no build target: ${scalaPaths.mkString("\n  ")}")
          Future.successful(())
        } else {
          val params = new CompileParams(targets.asJava)
          val name =
            targets.headOption
              .flatMap(buildTargets.info)
              .map(info => " " + info.getDisplayName)
              .getOrElse("")
          for {
            (elapsed, status) <- withTimer(
              s"compiled$name",
              reportStatus = true
            ) {
              statusBar.trackFuture(
                s"${config.icons.sync}Compiling$name",
                build.compile(params).asScala,
                showTimer = true
              )
            }
          } yield {
            status.getStatusCode match {
              case StatusCode.OK =>
                statusBar.addMessage(
                  s"${config.icons.check}Compiled$name ($elapsed)"
                )
              case StatusCode.ERROR =>
                statusBar.addMessage(
                  MetalsStatusParams(
                    s"${config.icons.alert}Compile error ($elapsed)",
                    command = ClientCommands.FocusDiagnostics.id
                  )
                )
              case StatusCode.CANCELLED =>
            }
          }
        }
      case _ =>
        Future.successful(())
    }
  }

  /**
   * Re-imports the sbt build if build files have changed.
   */
  private val onSbtBuildChanged =
    new BatchedFunction[AbsolutePath, BuildChange](onSbtBuildChangedUnbatched)
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

  private def newSymbolIndex(): OnDemandSymbolIndex = {
    OnDemandSymbolIndex(onError = {
      case e: ParseException =>
        scribe.error(e.toString())
      case NonFatal(e) =>
        scribe.error("unexpected error during source scanning", e)
    })
  }

}
