package scala.meta.internal.metals

import java.net.URI
import java.nio.file._
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Nil
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.Supermethods
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.callHierarchy.CallHierarchyProvider
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.clients.language.ForwardingMetalsBuildClient
import scala.meta.internal.metals.codeactions.CodeActionProvider
import scala.meta.internal.metals.codelenses.RunTestCodeLens
import scala.meta.internal.metals.codelenses.SuperMethodCodeLens
import scala.meta.internal.metals.codelenses.WorksheetCodeLens
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.BuildTargetClassesFinder
import scala.meta.internal.metals.debug.DebugDiscovery
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.doctor.MetalsServiceInfo
import scala.meta.internal.metals.findfiles._
import scala.meta.internal.metals.formatting.OnTypeFormattingProvider
import scala.meta.internal.metals.formatting.RangeFormattingProvider
import scala.meta.internal.metals.newScalaFile.NewFileProvider
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.internal.metals.testProvider.BuildTargetUpdate
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.mtags._
import scala.meta.internal.parsing.ClassFinderGranularity
import scala.meta.internal.parsing.DocumentSymbolProvider
import scala.meta.internal.parsing.FoldingRangeProvider
import scala.meta.internal.parsing.Trees
import scala.meta.internal.rename.RenameProvider
import scala.meta.internal.search.SymbolHierarchyOps
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.io.AbsolutePath
import scala.meta.metals.lsp.TextDocumentService
import scala.meta.parsers.ParseException
import scala.meta.pc.CancelToken
import scala.meta.tokenizers.TokenizeException

import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j._
import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.{lsp4j => l}

/**
 * Metals implementation of the Scala Language Service.
 * @param ec
 *  Execution context used for submitting tasks. This class DO NOT manage the
 *  lifecycle of this execution context.
 * @param sh
 *  Scheduled executor service used for scheduling tasks. This class DO NOT
 *  manage the lifecycle of this executor.
 * @param serverInputs
 *  Collection of different parameters used by Metals for running,
 *  which main purpose is allowing for custom behavior in tests.
 * @param workspace
 *  An absolute path to the workspace.
 * @param client
 *  Metals client used for sending notifications to the client. This class DO
 *  NOT manage the lifecycle of this client. It is the responsibility of the
 *  caller to shut down the client.
 * @param initializeParams
 *  Initialization parameters send by the client in the initialize request,
 *  which is the first request sent to the server by the client.
 */
abstract class MetalsLspService(
    ec: ExecutionContextExecutorService,
    val sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    val languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    val clientConfig: ClientConfiguration,
    val statusBar: StatusBar,
    getFocusedDocument: () => Option[AbsolutePath],
    val timerProvider: TimerProvider,
    val folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    bspStatus: BspStatus,
    val workDoneProgress: WorkDoneProgress,
    maxScalaCliServers: Int,
    moduleStatus: ModuleStatus,
) extends Folder(folder, folderVisibleName, isKnownMetalsProject = true)
    with Cancelable
    with TextDocumentService
    with IndexProviders
    with ModulesService {
  import serverInputs._

  def focusedDocument: Option[AbsolutePath] = getFocusedDocument()
  def shellRunner: ShellRunner

  @volatile
  var userConfig: UserConfiguration = initialUserConfig
  protected val userConfigPromise: Promise[Unit] = Promise()

  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)

  def getVisibleName: String = folderVisibleName.getOrElse(folder.toString())

  protected val cancelables = new MutableCancelable()
  val isCancelled = new AtomicBoolean(false)
  val wasInitialized = new AtomicBoolean(false)

  override def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      val buildShutdown = bspSession match {
        case Some(session) => session.shutdown()
        case None => Future.successful(())
      }
      try {
        cancelables.cancel()
      } catch {
        case NonFatal(_) =>
      }
      try buildShutdown.asJava.get(100, TimeUnit.MILLISECONDS)
      catch {
        case _: TimeoutException =>
      }
    }
  }

  implicit val executionContext: ExecutionContextExecutorService = ec

  protected val embedded: Embedded = register(
    new Embedded(workDoneProgress)
  )

  val tables: Tables = register(new Tables(folder, time))

  protected val mainBuildTargetsData = new TargetData

  val buildTargets: BuildTargets =
    BuildTargets.from(folder, mainBuildTargetsData, tables)

  implicit val reports: StdReportContext = new StdReportContext(
    folder.toNIO,
    _.flatMap { uri =>
      for {
        filePath <- Try(AbsolutePath(Paths.get(uri))).toOption
        buildTargetId <- buildTargets.inverseSources(filePath)
        name <- buildTargets.info(buildTargetId).map(_.getDisplayName())
      } yield name
    },
    ReportLevel.fromString(MetalsServerConfig.default.loglevel),
    reportTrackers = List(moduleStatus),
  )

  def javaHome = userConfig.javaHome

  protected val fingerprints = new MutableMd5Fingerprints
  val focusedDocumentBuildTarget =
    new AtomicReference[b.BuildTargetIdentifier]()
  val definitionIndex: OnDemandSymbolIndex = newSymbolIndex()

  def bspSession: Option[BspSession] = indexer.bspSession
  protected val savedFiles = new ActiveFiles(time)
  protected val recentlyOpenedFiles = new ActiveFiles(time)

  @volatile
  var excludedPackageHandler: ExcludedPackagesHandler =
    ExcludedPackagesHandler.default

  protected val mtags = new Mtags

  val symbolDocs = new Docstrings(definitionIndex)

  val fileChanges: FileChanges = new FileChanges(buildTargets, () => folder)

  val scalaVersionSelector = new ScalaVersionSelector(
    () => userConfig,
    buildTargets,
  )

  protected val downstreamTargets = new PreviouslyCompiledDownsteamTargets

  val sourceMapper: SourceMapper = SourceMapper(
    buildTargets,
    buffers,
  )

  protected val trees = new Trees(buffers, scalaVersionSelector)

  val buildTargetClasses =
    new BuildTargetClasses(buildTargets, trees)



  val compilations: Compilations = new Compilations(
    buildTargets,
    buildTargetClasses,
    languageClient,
    () => testProvider.refreshTestSuites.apply(()),
    () => {
      if (clientConfig.isDoctorVisibilityProvider())
        headDoctor.executeRefreshDoctor()
      else ()
    },
    () => Option(focusedDocumentBuildTarget.get()),
    worksheets => onWorksheetChanged(worksheets),
    onStartCompilation,
    () => userConfig,
    downstreamTargets,
    fileChanges,
    clientConfig.initialConfig.enableBestEffort,
  )
  var indexingPromise: Promise[Unit] = Promise[Unit]()
  def buildServerPromise: Promise[Unit]
  val parseTrees = new BatchedFunction[AbsolutePath, Unit](
    paths =>
      CancelableFuture(
        buildServerPromise.future
          .flatMap(_ => parseTreesAndPublishDiags(paths))
          .ignoreValue,
        Cancelable.empty,
      ),
    "trees",
  )


  protected val documentSymbolProvider = new DocumentSymbolProvider(
    trees,
    initializeParams.supportsHierarchicalDocumentSymbols,
  )

  protected val onTypeFormattingProvider =
    new OnTypeFormattingProvider(buffers, trees, () => userConfig)
  protected val rangeFormattingProvider =
    new RangeFormattingProvider(buffers, trees, () => userConfig)

  protected val foldingRangeProvider = new FoldingRangeProvider(
    trees,
    buffers,
    foldOnlyLines = initializeParams.foldOnlyLines,
    clientConfig.initialConfig.foldingRageMinimumSpan,
    scalaVersionSelector,
  )

  val diagnostics: Diagnostics = new Diagnostics(
    buffers,
    languageClient,
    clientConfig.initialConfig.statistics,
    Option(folder),
    scalaVersionSelector,
    buildTargets,
    downstreamTargets,
    initialServerConfig,
  )

  protected def semanticdbs(): Semanticdbs

  protected val connectionBspStatus =
    new ConnectionBspStatus(bspStatus, folder, clientConfig.icons())

  protected val bspErrorHandler: BspErrorHandler =
    new BspErrorHandler(
      () => bspSession,
      tables,
      connectionBspStatus,
    )

  val workspaceSymbols: WorkspaceSymbolProvider =
    new WorkspaceSymbolProvider(
      folder,
      buildTargets,
      definitionIndex,
      saveClassFileToDisk = !clientConfig.isVirtualDocumentSupported(),
      () => excludedPackageHandler,
      classpathSearchIndexer = classpathSearchIndexer,
    )

  protected def warnings: Warnings = NoopWarnings

  protected val definitionProvider: DefinitionProvider = new DefinitionProvider(
    folder,
    mtags,
    buffers,
    definitionIndex,
    semanticdbs,
    () => compilers,
    trees,
    buildTargets,
    scalaVersionSelector,
    saveDefFileToDisk = !clientConfig.isVirtualDocumentSupported(),
    sourceMapper,
  )

  val stacktraceAnalyzer: StacktraceAnalyzer = new StacktraceAnalyzer(
    folder,
    buffers,
    definitionProvider,
    clientConfig.icons(),
    clientConfig.commandInHtmlFormat(),
  )

  protected val testProvider: TestSuitesProvider = new TestSuitesProvider(
    buildTargets,
    buildTargetClasses,
    trees,
    definitionIndex,
    semanticdbs,
    buffers,
    clientConfig,
    () => userConfig,
    languageClient,
    getVisibleName,
    folder,
  )

  protected val codeLensProvider: CodeLensProvider = {
    val runTestLensProvider =
      new RunTestCodeLens(
        buildTargetClasses,
        buffers,
        scalaVersionSelector,
        buildTargets,
        clientConfig,
        () => userConfig,
        folder,
        diagnostics,
      )
    val goSuperLensProvider = new SuperMethodCodeLens(
      buffers,
      () => userConfig,
      scalaVersionSelector,
      clientConfig,
    )
    val worksheetCodeLens = new WorksheetCodeLens(clientConfig)

    new CodeLensProvider(
      codeLensProviders = List(
        runTestLensProvider,
        goSuperLensProvider,
        worksheetCodeLens,
        testProvider,
      ),
      semanticdbs,
      stacktraceAnalyzer,
    )
  }

  protected val formattingProvider: FormattingProvider = new FormattingProvider(
    folder,
    buffers,
    () => userConfig,
    languageClient,
    clientConfig,
    statusBar,
    workDoneProgress,
    clientConfig.icons(),
    tables,
    buildTargets,
  )

  protected val javaHighlightProvider: JavaDocumentHighlightProvider =
    new JavaDocumentHighlightProvider(
      definitionProvider,
      semanticdbs,
    )

  protected def onCreate(path: AbsolutePath): Unit = {
    buildTargets.onCreate(path)
    compilers.didChange(path, false)
  }

  protected val interactiveSemanticdbs: InteractiveSemanticdbs = {
    val javaInteractiveSemanticdb =
      JavaInteractiveSemanticdb.create(folder, buildTargets)
    register(
      new InteractiveSemanticdbs(
        folder,
        buildTargets,
        charset,
        tables,
        () => compilers,
        () => semanticDBIndexer,
        javaInteractiveSemanticdb,
        buffers,
        scalaCli,
      )
    )
  }

  protected val symbolSearch: MetalsSymbolSearch = new MetalsSymbolSearch(
    symbolDocs,
    workspaceSymbols,
    definitionProvider,
  )

  val worksheetProvider: WorksheetProvider = register(
    new WorksheetProvider(
      folder,
      buffers,
      buildTargets,
      languageClient,
      () => userConfig,
      workDoneProgress,
      diagnostics,
      embedded,
      compilations,
      scalaVersionSelector,
      clientConfig,
    )
  )

  val compilers: Compilers = register(
    new Compilers(
      folder,
      clientConfig,
      () => userConfig,
      buildTargets,
      buffers,
      symbolSearch,
      embedded,
      workDoneProgress,
      sh,
      initializeParams,
      () => excludedPackageHandler,
      scalaVersionSelector,
      trees,
      mtagsResolver,
      sourceMapper,
      worksheetProvider,
      () => referencesProvider,
    )
  )

  val referencesProvider: ReferenceProvider = new ReferenceProvider(
    folder,
    semanticdbs,
    buffers,
    definitionProvider,
    trees,
    buildTargets,
    compilers,
    scalaVersionSelector,
  )

  protected val packageProvider: PackageProvider =
    new PackageProvider(
      buildTargets,
      trees,
      referencesProvider,
      buffers,
      definitionProvider,
    )

  protected val newFileProvider: NewFileProvider = new NewFileProvider(
    languageClient,
    packageProvider,
    scalaVersionSelector,
    clientConfig.icons(),
    onCreate = path => {
      onCreate(path)
      onChange(List(path))
    },
  )

  protected val javaFormattingProvider: JavaFormattingProvider =
    new JavaFormattingProvider(
      buffers,
      () => userConfig,
      buildTargets,
    )

  val implementationProvider: ImplementationProvider =
    new ImplementationProvider(
      semanticdbs,
      folder,
      buffers,
      definitionProvider,
      scalaVersionSelector,
      compilers,
      buildTargets,
    )

  protected val symbolHierarchyOps: SymbolHierarchyOps =
    new SymbolHierarchyOps(
      folder,
      buildTargets,
      semanticdbs,
      definitionIndex,
      scalaVersionSelector,
      buffers,
    )

  protected val supermethods: Supermethods = new Supermethods(
    languageClient,
    definitionProvider,
    symbolHierarchyOps,
  )

  val semanticDBIndexer: SemanticdbIndexer = new SemanticdbIndexer(
    List(
      referencesProvider,
      implementationProvider,
      testProvider,
    ),
    buildTargets,
    folder,
  )

  protected val callHierarchyProvider: CallHierarchyProvider =
    new CallHierarchyProvider(
      folder,
      semanticdbs,
      definitionProvider,
      referencesProvider,
      clientConfig.icons(),
      () => compilers,
      trees,
      buildTargets,
      supermethods,
    )

  protected val renameProvider: RenameProvider = new RenameProvider(
    referencesProvider,
    implementationProvider,
    symbolHierarchyOps,
    definitionProvider,
    folder,
    languageClient,
    buffers,
    compilations,
    compilers,
    clientConfig,
    trees,
  )

  def buildHasErrors(path: scala.meta.io.AbsolutePath): Boolean =
    buildClient.buildHasErrors(path)

  protected val scalafixProvider: ScalafixProvider = ScalafixProvider(
    buffers,
    () => userConfig,
    folder,
    workDoneProgress,
    compilations,
    languageClient,
    buildTargets,
    interactiveSemanticdbs,
    tables,
    buildHasErrors,
  )

  protected val codeActionProvider: CodeActionProvider = new CodeActionProvider(
    compilers,
    buffers,
    buildTargets,
    scalafixProvider,
    trees,
    diagnostics,
    languageClient,
  )

  protected val inlayHintResolveProvider: InlayHintResolveProvider =
    new InlayHintResolveProvider(
      definitionProvider,
      compilers,
    )

  def optFileSystemSemanticdbs(): Option[FileSystemSemanticdbs] = None

  protected def fileDecoderProvider: FileDecoderProvider

  def loadedPresentationCompilerCount(): Int =
    compilers.loadedPresentationCompilerCount()

  protected val findTextInJars: FindTextInDependencyJars =
    new FindTextInDependencyJars(
      buildTargets,
      () => folder,
      languageClient,
      saveJarFileToDisk = !clientConfig.isVirtualDocumentSupported(),
    )

  private val metalsPasteProvider: MetalsPasteProvider =
    new MetalsPasteProvider(
      compilers,
      buildTargets,
      definitionProvider,
      trees,
    )

  def parseTreesAndPublishDiags(paths: Seq[AbsolutePath]): Future[Unit] = {
    Future
      .traverse(paths.distinct) { path =>
        if (path.isScalaFilename && buffers.contains(path)) {
          Future(diagnostics.onSyntaxError(path, trees.didChange(path)))
        } else {
          Future.successful(())
        }
      }
      .ignoreValue
  }

  def register[T <: Cancelable](cancelable: T): T = {
    cancelables.add(cancelable)
    cancelable
  }

  protected def loadFingerPrints(): Future[Unit] = Future {
    // load fingerprints from last execution
    fingerprints.addAll(tables.fingerprints.load())
  }

  def allActionCommandsIds = codeActionProvider.allActionCommandsIds

  def executeCodeActionCommand(
      params: l.ExecuteCommandParams,
      token: CancelToken,
  ): Future[Unit] = codeActionProvider.executeCommands(params, token)

  protected def registerNiceToHaveFilePatterns(): Unit = {
    for {
      params <- Option(initializeParams)
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
              clientConfig
                .globSyntax()
                .registrationOptions(
                  this.folder
                ),
            )
          ).asJava
        )
      )
    }
  }

  protected def onInitialized(): Future[Unit]

  def initialized(): Future[Unit] =
    if (wasInitialized.compareAndSet(false, true)) {
      registerNiceToHaveFilePatterns()

      for {
        _ <- loadFingerPrints()
        _ <-
          Future
            .sequence(
              List[Future[Unit]](
                onInitialized(),
                Future(workspaceSymbols.indexClasspath()),
                Future(formattingProvider.load()),
              )
            )
      } yield ()
    } else Future.unit

  def onShutdown(): Unit = {
    tables.fingerprints.save(fingerprints.getAllFingerprints().filter {
      case (path, _) => path.isScalaOrJava && !path.isDependencySource(folder)
    })
    cancel()
  }

  def setUserConfig(newConfig: UserConfiguration): UserConfiguration = {
    val old = userConfig
    userConfig = newConfig
    excludedPackageHandler = ExcludedPackagesHandler.fromUserConfiguration(
      userConfig.excludedPackages.getOrElse(Nil)
    )
    userConfigPromise.trySuccess(())
    old
  }

  def onUserConfigUpdate(newConfig: UserConfiguration): Future[Unit] = {
    val old = setUserConfig(newConfig)
    if (userConfig.excludedPackages != old.excludedPackages) {
      workspaceSymbols.indexClasspath()
    }

    userConfig.fallbackScalaVersion.foreach { version =>
      if (!ScalaVersions.isSupportedAtReleaseMomentScalaVersion(version)) {
        val params =
          Messages.UnsupportedScalaVersion.fallbackScalaVersionParams(
            version
          )
        languageClient.showMessage(params)
      }
    }

    if (
      userConfig.symbolPrefixes != old.symbolPrefixes ||
      userConfig.javaHome != old.javaHome
    ) {
      compilers.restartAll()
    }
    Future.unit
  }

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    // In some cases like peeking definition didOpen might be followed up by close
    // and we would lose the notion of the focused document
    recentlyOpenedFiles.add(path)
    focusedDocumentBuildTarget.set(
      buildTargets.inverseSources(path).getOrElse(null)
    )

    // Update md5 fingerprint from file contents on disk
    fingerprints.add(path, FileIO.slurp(path, charset))
    // Update in-memory buffer contents from LSP client
    buffers.put(path, params.getTextDocument.getText)

    val optVersion =
      Option.when(initializeParams.supportsVersionedWorkspaceEdits)(
        params.getTextDocument().getVersion()
      )

    packageProvider
      .workspaceEdit(path, params.getTextDocument().getText(), optVersion)
      .map(new ApplyWorkspaceEditParams(_))
      .foreach(languageClient.applyEdit)

    /**
     * Trigger compilation in preparation for definition requests for dependency
     * sources and standalone files, but wait for build tool information, so
     * that we don't try to generate it for project files
     */
    val interactive = buildServerPromise.future.map { _ =>
      interactiveSemanticdbs.textDocument(path)
    }

    val parser = parseTrees(path)

    if (path.isDependencySource(folder)) {
      parser.asJava
    } else {
      buildServerPromise.future.flatMap { _ =>
        def load(): Future[Unit] = {
          Future
            .sequence(
              List(
                compilations.compileFile(path, assumeDidNotChange = true),
                compilers.load(List(path)),
                parser,
                interactive,
                testProvider.didOpen(path),
              )
            )
            .ignoreValue
        }
        maybeImportFileAndLoad(path, load)
      }.asJava
    }
  }

  def maybeImportFileAndLoad(
      path: AbsolutePath,
      load: () => Future[Unit],
  ): Future[Unit]

  /**
   * Corresponds to LSP `didFocus` event.
   */
  def didFocus(
      uri: String
  ): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    scalaCli.didFocus(path)
    focusedDocumentBuildTarget.set(
      buildTargets.inverseSources(path).getOrElse(null)
    )
    // Don't trigger compilation on didFocus events under cascade compilation
    // because save events already trigger compile in inverse dependencies.
    if (path.isDependencySource(folder)) {
      CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    } else if (recentlyOpenedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(DidFocusResult.RecentlyActive)
    } else {
      compilations
        .compileFile(path, assumeDidNotChange = true)
        .map(_ => DidFocusResult.Compiled)
        .asJava
    }
  }

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = {
    val changesSize = params.getContentChanges.size()
    if (changesSize != 1) {
      scribe.debug(
        s"did change notification contained $changesSize content changes, expected 1"
      )
    }

    params.getContentChanges.asScala.lastOption match {
      case None => CompletableFuture.completedFuture(())
      case Some(change) =>
        val path = params.getTextDocument.getUri.toAbsolutePath
        buffers.put(path, change.getText)
        diagnostics.didChange(path)
        compilers.didChange(path, false)
        referencesProvider.didChange(path, change.getText)
        parseTrees(path).asJava
    }
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    buffers.remove(path)
    compilers.didClose(path)
    trees.didClose(path)
    diagnostics.onClose(path)
    interactiveSemanticdbs.onClose(path)
  }

  override def didSave(
      params: DidSaveTextDocumentParams
  ): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    savedFiles.add(path)
    Future
      .sequence(
        List(
          renameProvider.runSave(),
          parseTrees(path),
          onChange(List(path)),
        )
      )
      .ignoreValue
      .asJava
  }

  protected def didCompileTarget(report: CompileReport): Unit = {
    compilers.didCompile(report)
  }

  def didChangeWatchedFiles(
      events: List[FileEvent]
  ): Future[Unit] = {
    val importantEvents =
      events
        .filterNot(event =>
          event.getUri().toAbsolutePathSafe match {
            case None => true
            case Some(path) =>
              savedFiles.isRecentlyActive(path) || path.isDirectory
          }
        ) // de-duplicate didSave events.
        .toSeq
    val (deleteEvents, changeAndCreateEvents) =
      importantEvents.partition(_.getType().equals(FileChangeType.Deleted))
    val (bloopReportDelete, otherDeleteEvents) =
      deleteEvents.partition(
        _.getUri().toAbsolutePath.toNIO
          .startsWith(reports.bloop.maybeReportsDir)
      )
    if (bloopReportDelete.nonEmpty) connectionBspStatus.onReportsUpdate()
    otherDeleteEvents.map(_.getUri().toAbsolutePath).foreach(onDelete)
    onChange(changeAndCreateEvents.map(_.getUri().toAbsolutePath))
  }

  /**
   * This filter is an optimization and it is closely related to which files are
   * processed in [[didChangeWatchedFiles]]
   */
  protected def fileWatchFilter(path: Path): Boolean = {
    val abs = AbsolutePath(path)
    abs.isScalaOrJava || abs.isSemanticdb || abs.isInBspDirectory(folder)
  }

  protected def onChange(paths: Seq[AbsolutePath]): Future[Unit] = {
    val pathsWithFingerPrints =
      paths.map { path =>
        val fingerprint = fingerprints.add(path, FileIO.slurp(path, charset))
        (path, fingerprint)
      }

    Future
      .sequence(
        List(
          Future(indexer.reindexWorkspaceSources(paths)),
          compilations.compileFiles(pathsWithFingerPrints),
        ) ++ paths.map(f => Future(interactiveSemanticdbs.textDocument(f)))
      )
      .ignoreValue
  }

  protected def onDelete(path: AbsolutePath): Future[Unit] = {
    Future
      .sequence(
        List(
          compilations.compileFiles(List((path, Fingerprint.empty))),
          Future {
            diagnostics.didDelete(path)
            testProvider.onFileDelete(path)
          },
        )
      )
      .ignoreValue
  }

  override def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens.future { token =>
      definitionOrReferences(position, token).map(_.locations)
    }

  override def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens.future { token =>
      compilers.typeDefinition(position, token).map(_.locations)
    }

  override def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens.future { _ =>
      implementationProvider.implementations(position).map(_.asJava)
    }

  override def hover(params: HoverExtParams): CompletableFuture[Hover] = {
    CancelTokens.future { token =>
      compilers
        .hover(params, token)
        .map(_.map(_.toLsp()).orNull)
    }
  }

  def inlayHints(
      params: InlayHintParams
  ): CompletableFuture[util.List[InlayHint]] = {
    CancelTokens.future { token =>
      for {
        _ <- userConfigPromise.future
        hints <-
          if (userConfig.areSyntheticsEnabled())
            compilers.inlayHints(params, token)
          else Future.successful(List.empty[l.InlayHint].asJava)
        worksheet <- worksheetProvider.inlayHints(
          params.getTextDocument().getUri().toAbsolutePathSafe,
          token,
        )
      } yield (hints.asScala ++ worksheet).asJava
    }
  }

  def inlayHintResolve(
      inlayHint: InlayHint
  ): CompletableFuture[InlayHint] =
    CancelTokens.future { token =>
      inlayHintResolveProvider.resolve(inlayHint, token)
    }

  override def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] = {
    if (params.getTextDocument.getUri.toAbsolutePath.isJava)
      CancelTokens { _ => javaHighlightProvider.documentHighlight(params) }
    else
      CancelTokens.future { token =>
        compilers.documentHighlight(params, token)
      }
  }

  override def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] =
    CancelTokens { _ =>
      documentSymbolProvider
        .documentSymbols(params.getTextDocument().getUri().toAbsolutePath)
        .asJava
    }

  protected def optProjectRoot: Option[AbsolutePath] = None

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else {
        val projectRoot = optProjectRoot.getOrElse(folder)
        formattingProvider.format(path, projectRoot, token)
      }
    }

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { _ =>
      parseTrees
        .currentFuture()
        .map { _ =>
          val path = params.getTextDocument.getUri.toAbsolutePath
          if (path.isJava)
            javaFormattingProvider.format()
          else
            onTypeFormattingProvider.format(params).asJava
        }
    }

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { _ =>
      parseTrees
        .currentFuture()
        .map { _ =>
          val path = params.getTextDocument.getUri.toAbsolutePath
          if (path.isJava)
            javaFormattingProvider.format(params)
          else
            rangeFormattingProvider.format(params).asJava
        }
    }

  override def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[l.Range] =
    CancelTokens.future { token =>
      renameProvider.prepareRename(params, token).map(_.orNull)
    }

  override def rename(
      params: RenameParams
  ): CompletableFuture[WorkspaceEdit] =
    CancelTokens.future { token =>
      renameProvider.rename(params, token)
    }

  override def references(
      params: ReferenceParams
  ): CompletableFuture[util.List[Location]] =
    CancelTokens.future { _ =>
      referencesResult(params).map(getSortedLocations)
    }

  private def getSortedLocations(referencesResult: List[ReferencesResult]) =
    referencesResult
      .flatMap(_.locations)
      .groupBy(_.getUri())
      .flatMap { case (_, locs) =>
        locs.sortWith(sortByLocationPosition).distinct
      }
      .toSeq
      .asJava

  private def sortByLocationPosition(l1: Location, l2: Location): Boolean = {
    l1.getRange.getStart.getLine < l2.getRange.getStart.getLine
  }

  def referencesResult(
      params: ReferenceParams
  ): Future[List[ReferencesResult]] = {
    val timer = new Timer(time)
    referencesProvider.references(params).map { results =>
      if (clientConfig.initialConfig.statistics.isReferences) {
        if (results.forall(_.symbol.isEmpty)) {
          scribe.info(s"time: found 0 references in $timer")
        } else {
          scribe.info(
            s"time: found ${results.flatMap(_.locations).length} references to symbol '${results
                .map(_.symbol)
                .mkString("and")}' in $timer"
          )
        }
      }
      results
    }
  }

  override def semanticTokensFull(
      params: SemanticTokensParams
  ): CompletableFuture[SemanticTokens] = {
    CancelTokens.future { token =>
      for {
        _ <- userConfigPromise.future
        res <- compilers.semanticTokens(params, token).map { semanticTokens =>
          if (semanticTokens.getData().isEmpty()) null
          else semanticTokens
        }
      } yield res
    }
  }

  override def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]] =
    CancelTokens.future { token =>
      callHierarchyProvider.prepare(params, token).map(_.asJava)
    }

  override def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    CancelTokens.future { token =>
      callHierarchyProvider.incomingCalls(params, token).map(_.asJava)
    }

  override def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    CancelTokens.future { token =>
      callHierarchyProvider.outgoingCalls(params, token).map(_.asJava)
    }

  override def completion(
      params: CompletionParams
  ): CompletableFuture[CompletionList] =
    CancelTokens.future { token => compilers.completions(params, token) }

  override def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    CancelTokens.future { _ =>
      if (clientConfig.isCompletionItemResolve) {
        compilers.completionItemResolve(item)
      } else {
        Future.successful(item)
      }
    }

  override def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    CancelTokens.future { token =>
      compilers.signatureHelp(params, token)
    }

  override def codeAction(
      params: CodeActionParams
  ): CompletableFuture[util.List[l.CodeAction]] =
    CancelTokens.future { token =>
      codeActionProvider.codeActions(params, token).map(_.asJava)
    }

  override def codeActionResolve(
      params: CodeAction
  ): CompletableFuture[CodeAction] = {
    CancelTokens.future { token =>
      codeActionProvider
        .resolveCodeAction(params, token)
        .recover(
          getOptDisplayableMessage.andThen { msg =>
            languageClient
              .showMessage(MessageType.Info, msg)
            params
          }
        )
    }
  }

  override def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    CancelTokens.future { _ =>
      buildServerPromise.future.flatMap { _ =>
        timerProvider.timedThunk(
          "code lens generation",
          thresholdMillis = 1.second.toMillis,
        ) {
          val path = params.getTextDocument.getUri.toAbsolutePath
          codeLensProvider.findLenses(path).map(_.toList.asJava)
        }
      }
    }

  override def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] = {
    CancelTokens.future { _ =>
      val path = params.getTextDocument().getUri().toAbsolutePath
      if (path.isScala)
        parseTrees
          .currentFuture()
          .map(_ => foldingRangeProvider.getRangedForScala(path))
      else
        Future {
          foldingRangeProvider.getRangedForJava(path)
        }
    }
  }

  override def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]] = {
    CancelTokens.future { token =>
      compilers.selectionRange(params, token)
    }
  }

  def workspaceSymbol(
      params: WorkspaceSymbolParams,
      token: CancelToken,
  ): Future[List[SymbolInformation]] =
    indexingPromise.future.map { _ =>
      val timer = new Timer(time)
      val result =
        workspaceSymbols
          .search(params.getQuery, token, focusedDocument)
          .toList
      if (clientConfig.initialConfig.statistics.isWorkspaceSymbol) {
        scribe.info(
          s"time: found ${result.length} results for query '${params.getQuery}' in $timer"
        )
      }
      result
    }

  def workspaceSymbol(query: String): Seq[SymbolInformation] = {
    workspaceSymbols.search(query, focusedDocument)
  }

  def indexSources(): Future[Unit] = Future {
    indexer.indexWorkspaceSources(buildTargets.allWritableData)
  }

  def decodeFile(uri: String): Future[DecoderResponse] =
    fileDecoderProvider.decodedFileContents(uri)

  def discoverTestSuites(uri: Option[String]): Future[List[BuildTargetUpdate]] =
    Future {
      testProvider.discoverTests(uri.map(_.toAbsolutePath))
    }

  def runScalafix(uri: String): Future[ApplyWorkspaceEditResponse] =
    scalafixProvider
      .runAllRules(uri.toAbsolutePath)
      .flatMap(applyEdits(uri, _))

  def runScalafixRules(
      uri: String,
      rules: List[String],
  ): Future[ApplyWorkspaceEditResponse] =
    scalafixProvider
      .runRulesOrPrompt(uri.toAbsolutePath, rules)
      .flatMap(applyEdits(uri, _))

  def didPaste(
      params: MetalsPasteParams
  ): Future[ApplyWorkspaceEditResponse] = {
    metalsPasteProvider
      .didPaste(params, EmptyCancelToken)
      .flatMap(optEdit =>
        applyEdits(params.textDocument.getUri(), optEdit.toList)
      )
  }

  protected def applyEdits(
      uri: String,
      edits: List[TextEdit],
  ): Future[ApplyWorkspaceEditResponse] = languageClient
    .applyEdit(
      new l.ApplyWorkspaceEditParams(
        new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
      )
    )
    .asScala

  def chooseClass(
      uri: String,
      granurality: ClassFinderGranularity,
  ): Future[DecoderResponse] =
    fileDecoderProvider.chooseClassFromFile(
      uri.toAbsolutePath,
      granurality,
    )

  def cascadeCompile(): Future[Unit] =
    compilations.cascadeCompileFiles(buffers.open.toSeq)

  def cleanCompile(): Future[Unit] = compilations.recompileAll()

  def compileTarget(target: b.BuildTargetIdentifier): Future[b.CompileResult] =
    compilations.compileTarget(target)

  def cancelCompile(): Future[Unit] = Future {
    // We keep this in here to provide a way for clients that aren't work done progress cancel providers
    // to be able to cancel a long-running worksheet evaluation by canceling compilation.
    if (focusedDocument.exists(_.isWorksheet))
      worksheetProvider.cancel()

    compilations.cancel()
    scribe.info("compilation cancelled")
  }

  def restartCompiler(): Future[Unit] = Future { compilers.restartAll() }

  def getLocationForSymbol(symbol: String): Option[Location] =
    definitionProvider
      .fromSymbol(symbol, focusedDocument)
      .asScala
      .headOption

  def gotoSupermethod(
      textDocumentPositionParams: TextDocumentPositionParams
  ): CompletableFuture[Object] =
    Future {
      val command =
        supermethods.getGoToSuperMethodCommand(textDocumentPositionParams)
      command.foreach(languageClient.metalsExecuteClientCommand)
      scribe.debug(s"Executing GoToSuperMethod ${command}")
    }.asJavaObject

  def superMethodHierarchy(
      textDocumentPositionParams: TextDocumentPositionParams
  ): CompletableFuture[Object] =
    supermethods
      .jumpToSelectedSuperMethod(textDocumentPositionParams)
      .asJavaObject

  def resetNotifications(): Future[Unit] = Future {
    tables.dismissedNotifications.resetAll()
  }

  def createFile(
      directoryURI: Option[String],
      name: Option[String],
      fileType: Option[String],
      isScala: Boolean,
  ): CompletableFuture[Object] =
    newFileProvider
      .handleFileCreation(directoryURI.map(new URI(_)), name, fileType, isScala)
      .asJavaObject

  def startScalaCli(path: AbsolutePath): Future[Unit] = {
    if (scalaCli.loaded(path)) Future.unit
    else scalaCli.start(path)
  }

  def stopScalaCli(): Future[Unit] = scalaCli.stop()

  def copyWorksheetOutput(
      worksheetPath: AbsolutePath
  ): CompletableFuture[Object] = {
    worksheetProvider
      .copyWorksheetOutput(worksheetPath)
      .map { output =>
        if (output.nonEmpty) {
          output
        } else {
          languageClient.showMessage(Messages.Worksheets.unableToExport)
          ()
        }
      }
      .asJavaObject
  }

  def analyzeStackTrace(content: String): Option[ExecuteCommandParams] =
    stacktraceAnalyzer.analyzeCommand(content)

  def resolveStacktraceLocation(stacktraceLine: String): Option[Location] =
    stacktraceAnalyzer.resolveStacktraceLocationCommand(stacktraceLine)

  def findBuildTargetByDisplayName(target: String): Option[b.BuildTarget] =
    buildTargets.findByDisplayName(target)

  def willRenameFile(
      oldPath: AbsolutePath,
      newPath: AbsolutePath,
  ): Future[WorkspaceEdit] =
    packageProvider.willMovePath(oldPath, newPath)

  def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): Future[List[Location]] = findTextInJars.find(params)

  protected def onBuildTargetChanges(params: b.DidChangeBuildTarget): Unit

  protected def importAfterScalaCliChanges(
      servers: Iterable[ScalaCli]
  ): Iterable[Unit] =
    servers.map { server =>
      server
        .importBuild()
        .onComplete {
          case Success(()) =>
          case Failure(exception) =>
            scribe
              .error(
                s"Error re-importing for a Scala CLI build with path ${server.path}",
                exception,
              )
        }
    }

  val buildClient: ForwardingMetalsBuildClient =
    new ForwardingMetalsBuildClient(
      languageClient,
      diagnostics,
      buildTargets,
      clientConfig,
      statusBar,
      time,
      didCompileTarget,
      onBuildTargetDidCompile = { target =>
        worksheetProvider.onBuildTargetDidCompile(target)
      },
      onBuildTargetDidChangeFunc = params => {
        onBuildTargetChanges(params)
      },
      bspErrorHandler,
      workDoneProgress,
      moduleStatus,
    )

  protected val buildTargetClassesFinder: BuildTargetClassesFinder =
    new BuildTargetClassesFinder(
      buildTargets,
      buildTargetClasses,
      definitionIndex,
    )

  protected val debugDiscovery: DebugDiscovery = new DebugDiscovery(
    buildTargetClasses,
    buildTargets,
    buildClient,
    languageClient,
    semanticdbs,
    () => userConfig,
    folder,
    buildTargetClassesFinder,
  )

  protected val debugProvider: DebugProvider = register(
    new DebugProvider(
      folder,
      buildTargets,
      buildTargetClasses,
      compilations,
      languageClient,
      buildClient,
      buildTargetClassesFinder,
      stacktraceAnalyzer,
      clientConfig,
      compilers,
      statusBar,
      workDoneProgress,
      sourceMapper,
      () => userConfig,
      testProvider,
    )
  )
  buildClient.registerLogForwarder(debugProvider)

  def debugDiscovery(params: DebugDiscoveryParams): Future[DebugSession] =
    debugDiscovery
      .debugDiscovery(params)
      .flatMap(debugProvider.asSession)

  def createDebugSession(
      target: b.BuildTargetIdentifier
  ): Future[DebugSession] =
    debugProvider.createDebugSession(target).flatMap(debugProvider.asSession)

  def testClassSearch(
      params: DebugUnresolvedTestClassParams
  ): DebugProvider.TestClassSearch =
    new DebugProvider.TestClassSearch(debugProvider, params)

  def mainClassSearch(
      params: DebugUnresolvedMainClassParams
  ): DebugProvider.MainClassSearch =
    new DebugProvider.MainClassSearch(debugProvider, params)

  def startTestSuite(
      target: b.BuildTarget,
      params: ScalaTestSuitesDebugRequest,
  ): Future[DebugSession] = debugProvider
    .startTestSuite(target, params)
    .flatMap(debugProvider.asSession)

  def startDebugProvider(params: b.DebugSessionParams): Future[DebugSession] =
    debugProvider
      .ensureNoWorkspaceErrors(params.getTargets.asScala.toSeq)
      .flatMap(_ => debugProvider.asSession(params))

  def discoverMainClasses(
      unresolvedParams: DebugDiscoveryParams
  ): Future[b.DebugSessionParams] =
    debugDiscovery.runCommandDiscovery(unresolvedParams)

  def supportsBuildTarget(
      target: b.BuildTargetIdentifier
  ): Option[b.BuildTarget] = buildTargets.info(target)

  val scalaCli: ScalaCliServers = register(
    new ScalaCliServers(
      () => compilers,
      compilations,
      workDoneProgress,
      buffers,
      () => indexer.index(() => ()),
      () => diagnostics,
      tables,
      () => buildClient,
      languageClient,
      () => clientConfig.initialConfig,
      () => userConfig,
      parseTreesAndPublishDiags,
      buildTargets,
      maxScalaCliServers,
    )
  )

  def buildData(): Seq[Indexer.BuildTool]

  def resetService(): Unit = {
    interactiveSemanticdbs.reset()
    buildClient.reset()
    semanticDBIndexer.reset()
    worksheetProvider.reset()
    symbolSearch.reset()
  }

  def fileWatcher: FileWatcher

  protected def indexer: Indexer

  def projectInfo: MetalsServiceInfo

  val doctor: Doctor =
    new Doctor(
      folder,
      buildTargets,
      diagnostics,
      languageClient,
      tables,
      clientConfig,
      mtagsResolver,
      getVisibleName,
      projectInfo,
    )

  val folderReportsZippper: FolderReportsZippper =
    FolderReportsZippper(doctor, reports)

  protected def check(): Unit = {
    doctor.check(headDoctor)
  }

  protected def onWorksheetChanged(
      paths: Seq[AbsolutePath]
  ): Future[Unit] = {
    paths
      .find { path =>
        if (clientConfig.isDidFocusProvider() || focusedDocument.isDefined) {
          focusedDocument.contains(path) &&
          path.isWorksheet
        } else {
          path.isWorksheet
        }
      }
      .fold(Future.successful(()))(
        worksheetProvider.evaluateAndPublish(_, EmptyCancelToken)
      )
      .flatMap { _ =>
        // we need to refresh tokens for worksheets since dependencies could have been added
        languageClient.refreshSemanticTokens().asScala.map(_ => ())
      }
  }

  /**
   * Returns the definition location or reference locations of a symbol at a
   * given text document position. If the symbol represents the definition
   * itself, this method returns the reference locations, otherwise this returns
   * definition location. https://github.com/scalameta/metals/issues/755
   */
  def definitionOrReferences(
      positionParams: TextDocumentPositionParams,
      token: CancelToken = EmptyCancelToken,
      definitionOnly: Boolean = false,
  ): Future[DefinitionResult] = {
    val source = positionParams.getTextDocument.getUri.toAbsolutePath
    if (source.isScalaFilename || source.isJavaFilename) {
      val semanticDBDoc =
        semanticdbs().textDocument(source).documentIncludingStale
      (for {
        doc <- semanticDBDoc
        positionOccurrence = definitionProvider.positionOccurrence(
          source,
          positionParams.getPosition,
          doc,
        )
        occ <- positionOccurrence.occurrence
      } yield occ) match {
        case Some(occ) =>
          if (occ.role.isDefinition && !definitionOnly)
            getReferencesForGoToDefinition(positionParams, token)
          else definitionResult(positionParams, token)
        case None =>
          // Even if it failed to retrieve the symbol occurrence from semanticdb,
          // try to find its definitions from presentation compiler.
          definitionResult(positionParams, token).flatMap { definition =>
            def isOnDefinition = definition.locations.asScala.exists(
              _.getRange().encloses(positionParams.getPosition())
            )
            if (!definitionOnly && isOnDefinition)
              getReferencesForGoToDefinition(positionParams, token)
            else Future.successful(definition)
          }
      }
    } else {
      // Ignore non-scala files.
      Future.successful(DefinitionResult.empty)
    }
  }

  private def getReferencesForGoToDefinition(
      positionParams: TextDocumentPositionParams,
      token: CancelToken,
  ) = {
    val refParams = new ReferenceParams(
      positionParams.getTextDocument(),
      positionParams.getPosition(),
      new ReferenceContext(false),
    )
    referencesResult(refParams).flatMap { results =>
      if (results.flatMap(_.locations).isEmpty) {
        // Fallback again to the original behavior that returns
        // the definition location itself if no reference locations found,
        // for avoiding the confusing messages like "No definition found ..."
        definitionResult(positionParams, token)
      } else {
        Future.successful(
          DefinitionResult(
            locations = getSortedLocations(results),
            symbol = results.head.symbol,
            definition = None,
            semanticdb = None,
            querySymbol = results.head.symbol,
          )
        )
      }
    }

  }

  /**
   * Returns textDocument/definition in addition to the resolved symbol.
   *
   * The resolved symbol is used for testing purposes only.
   */
  def definitionResult(
      position: TextDocumentPositionParams,
      token: CancelToken = EmptyCancelToken,
  ): Future[DefinitionResult] = {
    val source = position.getTextDocument.getUri.toAbsolutePath
    if (source.isScalaFilename || source.isJavaFilename) {
      val result =
        timerProvider.timedThunk(
          "definition",
          clientConfig.initialConfig.statistics.isDefinition,
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

  protected def newSymbolIndex(): OnDemandSymbolIndex = {
    OnDemandSymbolIndex.empty(
      onError = {
        case e @ (_: ParseException | _: TokenizeException) =>
          scribe.error(e.toString)
        case e: IndexingExceptions.InvalidJarException =>
          scribe.warn(s"invalid jar: ${e.path}", e.getCause)
        case e: IndexingExceptions.PathIndexingException =>
          scribe.error(s"issues while parsing: ${e.path}", e.getCause)
        case e: IndexingExceptions.InvalidSymbolException =>
          reports.incognito.create(() =>
            Report(
              "invalid-symbol",
              s"""Symbol: ${e.symbol}""".stripMargin,
              e,
            )
          )
          scribe.error(s"searching for `${e.symbol}` failed", e.getCause)
        case _: NoSuchFileException =>
        // only comes for badly configured jar with `/Users` path added.
        case NonFatal(e) =>
          scribe.error("unexpected error during source scanning", e)
      },
      toIndexSource = path => sourceMapper.mappedTo(path).getOrElse(path),
    )
  }

  protected def clearBloopDir(folder: AbsolutePath): Unit = {
    try BloopDir.clear(folder)
    catch {
      case e: Throwable =>
        languageClient.showMessage(Messages.ResetWorkspaceFailed)
        scribe.error("Error while deleting directories inside .bloop", e)
    }
  }

  protected def clearFolders(folders: AbsolutePath*): Unit = {
    try {
      folders.foreach(_.deleteRecursively())
    } catch {
      case e: Throwable =>
        languageClient.showMessage(Messages.ResetWorkspaceFailed)
        scribe.error(
          s"Error while deleting directories inside ${folders.mkString(", ")}",
          e,
        )
    }
  }

  def getTastyForURI(uri: URI): Future[Either[String, String]] =
    fileDecoderProvider.getTastyForURI(uri)
}
