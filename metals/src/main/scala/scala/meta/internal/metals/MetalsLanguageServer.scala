package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.ScalacOptionsParams
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.StatusCode
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
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
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

  private def icons: Icons = config.icons
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
  private val messages = new Messages(icons)

  private val cancelables = new MutableCancelable()
  override def cancel(): Unit = cancelables.cancel()

  // These can't be instantiated until we know the workspace root directory.
  private var languageClient: MetalsLanguageClient = _
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

  def connectToLanguageClient(client: MetalsLanguageClient): Unit = {
    languageClient = client
    statusBar = new StatusBar(() => languageClient, time, progressTicks)
    embedded = register(new Embedded(icons, statusBar))
    LanguageClientLogger.languageClient = Some(client)
  }

  def register[T <: Cancelable](cancelable: T): T = {
    cancelables.add(cancelable)
    cancelable
  }

  private def updateWorkspaceDirectory(params: InitializeParams): Unit = {
    workspace = AbsolutePath(Paths.get(URI.create(params.getRootUri)))
    MetalsLogger.setupLspLogger(workspace, redirectSystemOut)
    startHttpServer()
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
        statusBar
      )
    )
    bloopServers = new BloopServers(
      sh,
      workspace,
      buildClient,
      config,
      icons,
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
      icons,
      statusBar
    )
  }

  @JsonRequest("initialize")
  def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult] = {
    Future {
      initializeParams = Option(params)
      updateWorkspaceDirectory(params)
      val capabilities = new ServerCapabilities()
      capabilities.setDefinitionProvider(true)
      capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
      if (config.isNoInitialized) {
        sh.schedule(
          new Runnable {
            override def run(): Unit = {
              initialized(new InitializedParams)
            }
          },
          1,
          TimeUnit.SECONDS
        )
      }
      new InitializeResult(capabilities)
    }.logError("initialize")
      .transform(identity, e => {
        cancel()
        e
      })
      .asJava
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
      val url = s"http://$host:$port"
      var render: () => String = () => ""
      var complete: HttpServerExchange => Unit = e => ()
      val server = MetalsHttpServer(
        host,
        port,
        this,
        () => render(),
        e => complete(e)
      )
      val newClient = new MetalsHttpClient(
        workspace,
        url,
        languageClient,
        () => server.reload(),
        charset,
        icons,
        time,
        sh
      )
      render = () => newClient.renderHtml
      complete = e => newClient.complete(e)
      languageClient = newClient
      LanguageClientLogger.languageClient = Some(newClient)
      server.start()
      cancelables.add(Cancelable(() => server.stop()))
    }
  }

  @JsonNotification("initialized")
  def initialized(params: InitializedParams): CompletableFuture[Unit] = {
    statusBar.start(sh, 0, 1, TimeUnit.SECONDS)
    registerFileWatchers()
    Future
      .sequence(
        List[Future[Unit]](
          quickConnectToBuildServer().ignoreValue,
          slowConnectToBuildServer(forceImport = false).ignoreValue
        )
      )
      .asJavaUnit
  }

  @JsonRequest("shutdown")
  def shutdown(): CompletableFuture[Unit] = {
    LanguageClientLogger.languageClient = None
    scribe.info("Shutting down...")
    try {
      cancelables.cancel()
    } catch {
      case NonFatal(e) =>
        scribe.error("cancellation error", e)
    }
    sh.shutdownNow()
    buildServer match {
      case Some(value) =>
        value
          .shutdown()
          .logErrorAndContinue("shutting down build server")
          .asJava
      case None => Future.successful(()).asJava
    }
  }

  @JsonNotification("exit")
  def exit(): Unit = {
    System.exit(0)
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
    // TODO(olafur): Handle notification changes.
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

  @JsonRequest("workspace/executeCommand")
  def executeCommand(params: ExecuteCommandParams): CompletableFuture[Unit] =
    params.getCommand match {
      case ServerCommands.ScanWorkspaceSources =>
        Future {
          buildTargets.sourceDirectories.foreach(indexSourceDirectory)
        }.asJavaUnit
      case ServerCommands.ImportBuild =>
        slowConnectToBuildServer(forceImport = true).asJavaUnit
      case ServerCommands.ConnectBuildServer =>
        quickConnectToBuildServer().asJavaUnit
      case ServerCommands.OpenBrowser(url) =>
        CompletableFuture.completedFuture(Urls.openBrowser(url))
      case els =>
        scribe.error(s"Unknown command '$els'")
        CompletableFuture.completedFuture(())
    }

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] = {
    buildTools.asSbt match {
      case None =>
        scribe.warn(
          s"Skipping build import for unsupport build tool $buildTools"
        )
        Future.successful(BuildChange.None)
      case Some(sbt) =>
        SbtChecksum.current(workspace) match {
          case None =>
            scribe.warn(s"Skipping build import, no checksum.")
            Future.successful(BuildChange.None)
          case Some(current) =>
            slowConnectToBuildServer(forceImport, sbt, current)
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
        _ <- build.initialize()
        _ <- installWorkspaceBuildTargets(build)
      } yield ()

      for {
        _ <- statusBar.trackFuture("Importing build", importingBuild)
        _ = statusBar.addMessage(s"${icons.rocket}Imported build!")
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
        override def preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val path = AbsolutePath(dir)
          if (path.resolve("META-INF").isDirectory) {
            FileVisitResult.SKIP_SUBTREE
          } else if (dir.endsWith(".bloop") || dir.endsWith(".metals")) {
            FileVisitResult.SKIP_SUBTREE
          } else {
            super.preVisitDirectory(dir, attrs)
          }
        }
      }
    )
  }
  private def timed[T](didWhat: String, reportStatus: Boolean = false)(
      thunk: => Future[T]
  ): Future[T] = {
    withTimer(didWhat, reportStatus)(thunk).map {
      case (_, value) => value
    }
  }

  private def withTimer[T](didWhat: String, reportStatus: Boolean = false)(
      thunk: => Future[T]
  ): Future[(Timer, T)] = {
    val elapsed = new Timer(time)
    val result = thunk
    result.map { value =>
      if (elapsed.isLogWorthy) {
        scribe.info(s"time: $didWhat in $elapsed ")
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
        JdkSources().foreach(zip => index.addSourceJar(zip))
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
                s"${icons.sync}Compiling$name",
                build.compile(params).asScala,
                showTimer = true
              )
            }
          } yield {
            status.getStatusCode match {
              case StatusCode.OK =>
                statusBar.addMessage(s"${icons.check}Compiled$name in $elapsed")
              case StatusCode.ERROR =>
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
    val result: Future[BuildChange] =
      if (isBuildChange) {
        slowConnectToBuildServer(forceImport = false)
      } else {
        Future.successful(BuildChange.None)
      }
    result
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
      val result = definitionProvider.definition(source, position)
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
