package scala.meta.internal.metals

import java.net.URI
import java.nio.file._
import java.util
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Nil
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.builds.BuildToolSelector
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.WorkspaceReload
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.decorations.SyntheticHoverProvider
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
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.findfiles._
import scala.meta.internal.metals.newScalaFile.NewFileProvider
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.internal.metals.testProvider.BuildTargetUpdate
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.metals.watcher.FileWatcherEvent
import scala.meta.internal.metals.watcher.FileWatcherEvent.EventType
import scala.meta.internal.mtags._
import scala.meta.internal.parsing.ClassFinder
import scala.meta.internal.parsing.ClassFinderGranularity
import scala.meta.internal.parsing.DocumentSymbolProvider
import scala.meta.internal.parsing.FoldingRangeProvider
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.internal.remotels.RemoteLanguageServer
import scala.meta.internal.rename.RenameProvider
import scala.meta.internal.tvp._
import scala.meta.internal.worksheets.DecorationWorksheetPublisher
import scala.meta.internal.worksheets.WorksheetProvider
import scala.meta.internal.worksheets.WorkspaceEditWorksheetPublisher
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
    sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    timerProvider: TimerProvider,
    initTreeView: () => Unit,
    folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
    maxScalaCliServers: Int,
) extends Folder(folder, folderVisibleName, isKnownMetalsProject = true)
    with Cancelable
    with TextDocumentService {
  import serverInputs._

  protected def bspSession: Option[BspSession]
  protected def buildServerPromise: Promise[Unit]
  protected def bspErrorHandler: BspErrorHandler
  protected def doctor: Doctor
  protected def optBuildTools: Option[BuildTools]
  protected def indexerBuildTools: Seq[Indexer.BuildTool] =
    scalaCli.lastImportedBuilds.map {
      case (lastImportedBuild, buildTargetsData) =>
        Indexer
          .BuildTool("scala-cli", buildTargetsData, lastImportedBuild)
    }

  @volatile
  protected var userConfig: UserConfiguration = initialUserConfig
  protected val userConfigPromise: Promise[Unit] = Promise()

  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)

  def getVisibleName: String = folderVisibleName.getOrElse(folder.toString())

  protected val cancelables = new MutableCancelable()
  val isCancelled = new AtomicBoolean(false)

  override def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      try {
        cancelables.cancel()
      } catch {
        case NonFatal(_) =>
      }
    }
  }

  protected implicit val executionContext: ExecutionContextExecutorService = ec

  protected val embedded: Embedded = register(new Embedded(statusBar))

  val tables: Tables = register(new Tables(folder, time))

  implicit val reports: StdReportContext = new StdReportContext(
    folder.toNIO,
    _.flatMap { uri =>
      for {
        filePath <- uri.toAbsolutePathSafe
        buildTargetId <- buildTargets.inverseSources(filePath)
        name <- buildTargets.info(buildTargetId).map(_.getDisplayName())
      } yield name
    },
    ReportLevel.fromString(MetalsServerConfig.default.loglevel),
  )

  val folderReportsZippper: FolderReportsZippper =
    FolderReportsZippper(doctor.getTargetsInfoForReports, reports)

  def javaHome = userConfig.javaHome
  protected val optJavaHome: Option[AbsolutePath] =
    JdkSources.defaultJavaHome(javaHome).headOption
  protected val maybeJdkVersion: Option[JdkVersion] =
    JdkVersion.maybeJdkVersionFromJavaHome(optJavaHome)

  protected val fingerprints = new MutableMd5Fingerprints
  protected val mtags = new Mtags
  protected val focusedDocumentBuildTarget =
    new AtomicReference[b.BuildTargetIdentifier]()
  protected val definitionIndex: OnDemandSymbolIndex = newSymbolIndex()
  protected val symbolDocs = new Docstrings(definitionIndex)
  protected val savedFiles = new ActiveFiles(time)
  protected val recentlyOpenedFiles = new ActiveFiles(time)
  var excludedPackageHandler: ExcludedPackagesHandler =
    ExcludedPackagesHandler.default

  protected val mainBuildTargetsData = new TargetData
  val buildTargets: BuildTargets =
    BuildTargets.from(folder, mainBuildTargetsData, tables)

  protected val buildTargetClasses =
    new BuildTargetClasses(buildTargets)

  protected val sourceMapper: SourceMapper = SourceMapper(
    buildTargets,
    buffers,
  )

  protected val scalaVersionSelector = new ScalaVersionSelector(
    () => userConfig,
    buildTargets,
  )
  protected val remote = new RemoteLanguageServer(
    () => folder,
    () => userConfig,
    initialServerConfig,
    buffers,
    buildTargets,
  )

  val compilations: Compilations = new Compilations(
    buildTargets,
    buildTargetClasses,
    () => folder,
    languageClient,
    () => testProvider.refreshTestSuites(),
    () => {
      if (clientConfig.isDoctorVisibilityProvider())
        headDoctor.executeRefreshDoctor()
      else ()
    },
    buildTarget => focusedDocumentBuildTarget.get() == buildTarget,
    worksheets => onWorksheetChanged(worksheets),
    onStartCompilation,
  )

  protected val fileWatcher: FileWatcher = register(
    new FileWatcher(
      initialServerConfig,
      () => folder,
      buildTargets,
      fileWatchFilter,
      params => {
        didChangeWatchedFiles(params)
      },
    )
  )

  var indexingPromise: Promise[Unit] = Promise[Unit]()
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

  val pauseables: Pauseable = Pauseable.fromPausables(
    parseTrees ::
      compilations.pauseables
  )

  protected val trees = new Trees(buffers, scalaVersionSelector)

  protected val documentSymbolProvider = new DocumentSymbolProvider(
    trees,
    initializeParams.supportsHierarchicalDocumentSymbols,
  )

  protected val foldingRangeProvider = new FoldingRangeProvider(
    trees,
    buffers,
    foldOnlyLines = initializeParams.foldOnlyLines,
  )

  protected val diagnostics: Diagnostics = new Diagnostics(
    buffers,
    languageClient,
    clientConfig.initialConfig.statistics,
    Option(folder),
    trees,
  )

  protected val warnings: Warnings = new Warnings(
    folder,
    buildTargets,
    statusBar,
    clientConfig.icons,
    optBuildTools,
    compilations.isCurrentlyCompiling,
  )

  protected val fileSystemSemanticdbs: FileSystemSemanticdbs =
    new FileSystemSemanticdbs(
      buildTargets,
      charset,
      folder,
      fingerprints,
    )

  protected val interactiveSemanticdbs: InteractiveSemanticdbs = {
    val javaInteractiveSemanticdb = maybeJdkVersion.map(jdkVersion =>
      JavaInteractiveSemanticdb.create(folder, buildTargets, jdkVersion)
    )
    register(
      new InteractiveSemanticdbs(
        folder,
        buildTargets,
        charset,
        languageClient,
        tables,
        statusBar,
        () => compilers,
        clientConfig,
        () => semanticDBIndexer,
        javaInteractiveSemanticdb,
        buffers,
      )
    )
  }

  protected val semanticdbs: Semanticdbs = AggregateSemanticdbs(
    List(
      fileSystemSemanticdbs,
      interactiveSemanticdbs,
    )
  )

  val buildClient: ForwardingMetalsBuildClient =
    new ForwardingMetalsBuildClient(
      languageClient,
      diagnostics,
      buildTargets,
      clientConfig,
      statusBar,
      time,
      report => {
        didCompileTarget(report)
        compilers.didCompile(report)
      },
      onBuildTargetDidCompile = { target =>
        treeView.onBuildTargetDidCompile(target) match {
          case Some(toUpdate) =>
            languageClient.metalsTreeViewDidChange(
              TreeViewDidChangeParams(toUpdate)
            )
          case None =>
        }
        worksheetProvider.onBuildTargetDidCompile(target)
      },
      onBuildTargetDidChangeFunc = params => {
        onBuildTargetChanges(params)
      },
      bspErrorHandler,
    )

  protected val workspaceSymbols: WorkspaceSymbolProvider =
    new WorkspaceSymbolProvider(
      folder,
      buildTargets,
      definitionIndex,
      saveClassFileToDisk = !clientConfig.isVirtualDocumentSupported(),
      () => excludedPackageHandler,
      classpathSearchIndexer = classpathSearchIndexer,
    )

  protected val definitionProvider: DefinitionProvider = new DefinitionProvider(
    folder,
    mtags,
    buffers,
    definitionIndex,
    semanticdbs,
    warnings,
    () => compilers,
    remote,
    trees,
    buildTargets,
    scalaVersionSelector,
    saveDefFileToDisk = !clientConfig.isVirtualDocumentSupported(),
    sourceMapper,
    workspaceSymbols,
  )

  val stacktraceAnalyzer: StacktraceAnalyzer = new StacktraceAnalyzer(
    folder,
    buffers,
    definitionProvider,
    clientConfig.icons,
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
        buildTargets,
        clientConfig,
        () => userConfig,
        trees,
        folder,
      )
    val goSuperLensProvider = new SuperMethodCodeLens(
      buffers,
      () => userConfig,
      clientConfig,
      trees,
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

  protected val implementationProvider: ImplementationProvider =
    new ImplementationProvider(
      semanticdbs,
      folder,
      definitionIndex,
      buildTargets,
      buffers,
      definitionProvider,
      trees,
      scalaVersionSelector,
    )

  protected val supermethods: Supermethods = new Supermethods(
    languageClient,
    definitionProvider,
    implementationProvider,
  )

  protected val referencesProvider: ReferenceProvider = new ReferenceProvider(
    folder,
    semanticdbs,
    buffers,
    definitionProvider,
    remote,
    trees,
    buildTargets,
  )

  protected val syntheticHoverProvider: SyntheticHoverProvider =
    new SyntheticHoverProvider(
      folder,
      semanticdbs,
      buffers,
      fingerprints,
      charset,
      clientConfig,
      () => userConfig,
      trees,
    )

  val classpathTreeIndex = new IndexedSymbols(
    isStatisticsEnabled = clientConfig.initialConfig.statistics.isTreeView
  )

  protected val semanticDBIndexer: SemanticdbIndexer = new SemanticdbIndexer(
    List(
      referencesProvider,
      implementationProvider,
      testProvider,
      classpathTreeIndex,
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
      clientConfig.icons,
      () => compilers,
      trees,
      buildTargets,
      supermethods,
    )

  protected val javaHighlightProvider: JavaDocumentHighlightProvider =
    new JavaDocumentHighlightProvider(
      definitionProvider,
      semanticdbs,
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
    clientConfig.icons,
  )

  protected val symbolSearch: MetalsSymbolSearch = new MetalsSymbolSearch(
    symbolDocs,
    workspaceSymbols,
    definitionProvider,
  )

  val worksheetProvider: WorksheetProvider = {
    val worksheetPublisher =
      if (clientConfig.isDecorationProvider)
        new DecorationWorksheetPublisher(
          clientConfig.isInlineDecorationProvider()
        )
      else
        new WorkspaceEditWorksheetPublisher(buffers, trees)

    register(
      new WorksheetProvider(
        folder,
        buffers,
        buildTargets,
        languageClient,
        () => userConfig,
        statusBar,
        diagnostics,
        embedded,
        worksheetPublisher,
        compilations,
        scalaVersionSelector,
      )
    )
  }

  protected val compilers: Compilers = register(
    new Compilers(
      folder,
      clientConfig,
      () => userConfig,
      buildTargets,
      buffers,
      symbolSearch,
      embedded,
      statusBar,
      sh,
      initializeParams,
      () => excludedPackageHandler,
      scalaVersionSelector,
      trees,
      mtagsResolver,
      sourceMapper,
      worksheetProvider,
    )
  )

  protected val renameProvider: RenameProvider = new RenameProvider(
    referencesProvider,
    implementationProvider,
    definitionProvider,
    folder,
    languageClient,
    buffers,
    compilations,
    compilers,
    clientConfig,
    trees,
  )

  protected val debugProvider: DebugProvider = register(
    new DebugProvider(
      folder,
      buildTargets,
      buildTargetClasses,
      compilations,
      languageClient,
      buildClient,
      definitionIndex,
      stacktraceAnalyzer,
      clientConfig,
      semanticdbs,
      compilers,
      statusBar,
      sourceMapper,
      () => userConfig,
      testProvider,
    )
  )

  protected val scalafixProvider: ScalafixProvider = ScalafixProvider(
    buffers,
    () => userConfig,
    folder,
    statusBar,
    compilations,
    languageClient,
    buildTargets,
    buildClient,
    interactiveSemanticdbs,
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

  // val gitHubIssueFolderInfo = new GitHubIssueFolderInfo(
  //   () => tables.buildTool.selectedBuildTool(),
  //   buildTargets,
  //   () => bspSession,
  //   () => bspConnector.resolve(),
  //   buildTools,
  // )

  protected val fileDecoderProvider: FileDecoderProvider =
    new FileDecoderProvider(
      folder,
      compilers,
      buildTargets,
      () => userConfig,
      shellRunner,
      fileSystemSemanticdbs,
      interactiveSemanticdbs,
      languageClient,
      clientConfig,
      new ClassFinder(trees),
    )

  protected val workspaceReload: WorkspaceReload = new WorkspaceReload(
    folder,
    languageClient,
    tables,
  )

  new BuildToolSelector(
    languageClient,
    tables,
  )

  def loadedPresentationCompilerCount(): Int =
    compilers.loadedPresentationCompilerCount()

  val treeView =
    new FolderTreeViewProvider(
      new Folder(folder, folderVisibleName, true),
      buildTargets,
      () => buildClient.ongoingCompilations(),
      definitionIndex,
      () => userConfig,
      scalaVersionSelector,
      classpathTreeIndex,
    )

  protected val findTextInJars: FindTextInDependencyJars =
    new FindTextInDependencyJars(
      buildTargets,
      () => folder,
      languageClient,
      saveJarFileToDisk = !clientConfig.isVirtualDocumentSupported(),
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

  def allActionCommandsIds = codeActionProvider.allActionCommandsIds

  def executeCodeActionCommand(
      params: l.ExecuteCommandParams,
      token: CancelToken,
  ): Future[Unit] = codeActionProvider.executeCommands(params, token)

  val isInitialized = new AtomicBoolean(false)

  def initialized(): Future[Unit] = {
    tables.connect()
    initTreeView()
    Future(workspaceSymbols.indexClasspath())
  }

  def onShutdown(): Unit = {
    cancel()
  }

  def setUserConfig(newConfig: UserConfiguration): UserConfiguration = {
    val old = userConfig
    userConfig = newConfig
    userConfigPromise.trySuccess(())
    old
  }

  def onUserConfigUpdate(newConfig: UserConfiguration): Future[Unit] = {
    val old = setUserConfig(newConfig)
    if (userConfig.excludedPackages != old.excludedPackages) {
      excludedPackageHandler = ExcludedPackagesHandler.fromUserConfiguration(
        userConfig.excludedPackages.getOrElse(Nil)
      )
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

    if (userConfig.symbolPrefixes != old.symbolPrefixes) {
      compilers.restartAll()
    }

    if (
      userConfig.showImplicitArguments != old.showImplicitArguments ||
      userConfig.showImplicitConversionsAndClasses != old.showImplicitConversionsAndClasses ||
      userConfig.showInferredType != old.showInferredType
    ) {
      buildServerPromise.future.flatMap { _ =>
        focusedDocument().map(publishSynthetics(_, force = true))
          .getOrElse(Future.successful(()))
      }
    } else {
      Future.successful(())
    }
  }

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    // In some cases like peeking definition didOpen might be followed up by close
    // and we would lose the notion of the focused document
    recentlyOpenedFiles.add(path)

    // Update md5 fingerprint from file contents on disk
    fingerprints.add(path, FileIO.slurp(path, charset))
    // Update in-memory buffer contents from LSP client
    buffers.put(path, params.getTextDocument.getText)

    packageProvider
      .workspaceEdit(path)
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

    val publishSynthetics0 = for {
      _ <- Future.sequence(List(parseTrees(path), interactive))
      _ <- Future.sequence(
        List(
          publishSynthetics(path),
          testProvider.didOpen(path),
        )
      )
    } yield ()

    if (path.isDependencySource(folder)) {
      CancelTokens { _ =>
        // publish diagnostics
        interactiveSemanticdbs.didFocus(path)
        ()
      }
    } else {
      buildServerPromise.future.flatMap { _ =>
        def load(): Future[Unit] = {
          val compileAndLoad =
            Future.sequence(
              List(
                compilers.load(List(path)),
                compilations.compileFile(path),
              )
            )
          Future
            .sequence(
              List(
                compileAndLoad,
                publishSynthetics0,
              )
            )
            .ignoreValue
        }
        for {
          _ <- maybeAmendScalaCliBspConfig(path)
          _ <- maybeImportScript(path).getOrElse(load())
        } yield ()
      }.asJava
    }
  }

  def publishSynthetics(
      path: AbsolutePath,
      force: Boolean = false,
  ): Future[Unit] = {
    CancelTokens.future { token =>
      val shouldShow = (force || userConfig.areSyntheticsEnabled()) &&
        clientConfig.isInlineDecorationProvider()
      if (shouldShow) {
        compilers.syntheticDecorations(path, token).map { decorations =>
          val params = new PublishDecorationsParams(
            path.toURI.toString(),
            decorations.asScala.toArray,
            if (clientConfig.isInlineDecorationProvider()) true else null,
          )
          languageClient.metalsPublishDecorations(params)
        }
      } else Future.successful(())
    }.asScala
  }

  protected def maybeAmendScalaCliBspConfig(file: AbsolutePath): Future[Unit] =
    Future.successful(())

  def didFocus(
      uri: String
  ): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    buildTargets
      .inverseSources(path)
      .foreach(focusedDocumentBuildTarget.set)
    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
    scalaCli.didFocus(path)
    // Don't trigger compilation on didFocus events under cascade compilation
    // because save events already trigger compile in inverse dependencies.
    if (path.isDependencySource(folder)) {
      publishSynthetics(path)
      CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    } else if (recentlyOpenedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(DidFocusResult.RecentlyActive)
    } else {
      publishSynthetics(path)
      worksheetProvider.onDidFocus(path)
      buildTargets.inverseSources(path) match {
        case Some(target) =>
          val isAffectedByCurrentCompilation =
            path.isWorksheet ||
              buildTargets.isInverseDependency(
                target,
                compilations.currentlyCompiling.toList,
              )

          def isAffectedByLastCompilation: Boolean =
            !compilations.wasPreviouslyCompiled(target) &&
              buildTargets.isInverseDependency(
                target,
                compilations.previouslyCompiled.toList,
              )

          val needsCompile =
            isAffectedByCurrentCompilation || isAffectedByLastCompilation
          if (needsCompile) {
            compilations
              .compileFile(path)
              .map(_ => DidFocusResult.Compiled)
              .asJava
          } else {
            CompletableFuture.completedFuture(
              DidFocusResult.AlreadyCompiled
            )
          }
        case None =>
          CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
      }
    }
  }

  def pause(): Unit = pauseables.pause()

  def unpause(): Unit = pauseables.unpause()

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] =
    params.getContentChanges.asScala.headOption match {
      case None => CompletableFuture.completedFuture(())
      case Some(change) =>
        val path = params.getTextDocument.getUri.toAbsolutePath
        buffers.put(path, change.getText)
        diagnostics.didChange(path)
        parseTrees(path)
          .flatMap { _ =>
            publishSynthetics(path)
          }
          .ignoreValue
          .asJava
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
    // read file from disk, we only remove files from buffers on didClose.
    buffers.put(path, path.toInput.text)
    Future
      .sequence(
        List(
          renameProvider.runSave(),
          parseTrees(path),
          onChange(List(path)),
        ) ++ // if we fixed the script, we might need to retry connection
          maybeImportScript(
            path
          )
      )
      .ignoreValue
      .asJava
  }

  protected def didCompileTarget(report: CompileReport): Unit = {
    if (!isReliableFileWatcher) {
      // NOTE(olafur) this step is exclusively used when running tests on
      // non-Linux computers to avoid flaky failures caused by delayed file
      // watching notifications. The SemanticDB indexer depends on file watching
      // notifications to pick up `*.semanticdb` file updates and there's no
      // reliable way to await until those notifications appear.
      for {
        targetroot <- buildTargets.targetRoots(report.getTarget)
        semanticdb = targetroot.resolve(Directories.semanticdb)
        generatedFile <- semanticdb.listRecursive
      } {
        val event = FileWatcherEvent.createOrModify(generatedFile.toNIO)
        didChangeWatchedFiles(event).get()
      }
    }
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
    onDeleteWatchedFiles(deleteEvents.flatMap(_.getUri().toAbsolutePathSafe))
    onChange(changeAndCreateEvents.map(_.getUri().toAbsolutePath))
  }

  protected def onDeleteWatchedFiles(files: List[AbsolutePath]): Unit = files.foreach(onDelete)

  /**
   * This filter is an optimization and it is closely related to which files are
   * processed in [[didChangeWatchedFiles]]
   */
  protected def fileWatchFilter(path: Path): Boolean = {
    val abs = AbsolutePath(path)
    abs.isScalaOrJava || abs.isSemanticdb || abs.isBuild ||
    abs.isInBspDirectory(folder)
  }

  /**
   * Callback that is executed on a file change event by the file watcher.
   *
   * Note that if you are adding processing of another kind of a file, be sure
   * to include it in the [[fileWatchFilter]]
   *
   * This method is run synchronously in the FileWatcher, so it should not do
   * anything expensive on the main thread
   */
  protected def didChangeWatchedFiles(
      event: FileWatcherEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path)
    val isScalaOrJava = path.isScalaOrJava

    if (isScalaOrJava && event.eventType == EventType.Delete) {
      onDelete(path).asJava
    } else if (
      isScalaOrJava &&
      !path.isDirectory &&
      !savedFiles.isRecentlyActive(path) &&
      !buffers.contains(path)
    ) {
      event.eventType match {
        case EventType.CreateOrModify =>
          buildTargets.onCreate(path)
        case _ =>
      }
      onChange(List(path)).asJava
    } else if (path.isSemanticdb) {
      val semanticdbPath = SemanticdbPath(path)
      Future {
        event.eventType match {
          case EventType.Delete =>
            semanticDBIndexer.onDelete(semanticdbPath)
          case EventType.CreateOrModify =>
            semanticDBIndexer.onChange(semanticdbPath)
          case EventType.Overflow =>
            semanticDBIndexer.onOverflow(semanticdbPath)
        }
      }.asJava
    } else {
      CompletableFuture.completedFuture(())
    }
  }

  protected def onChange(paths: Seq[AbsolutePath]): Future[Unit] = {
    paths.foreach { path =>
      fingerprints.add(path, FileIO.slurp(path, charset))
    }

    Future
      .sequence(
        List(
          Future(indexer.reindexWorkspaceSources(paths)),
          compilations
            .compileFiles(paths, Option(focusedDocumentBuildTarget.get())),
        ) ++ paths.map(f => Future(interactiveSemanticdbs.textDocument(f)))
      ).ignoreValue
  }

  protected def onDelete(path: AbsolutePath): Future[Unit] = {
    Future
      .sequence(
        List(
          compilations
            .compileFiles(List(path), Option(focusedDocumentBuildTarget.get())),
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
        .map { hover =>
          syntheticHoverProvider.addSyntheticsHover(
            params,
            hover.map(_.toLsp()),
          )
        }
        .map(
          _.orElse {
            val path = params.textDocument.getUri.toAbsolutePath
            if (path.isWorksheet)
              worksheetProvider.hover(path, params.getPosition)
            else
              None
          }.orNull
        )
    }
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

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] = Future(
    List.empty[TextEdit].asJava
  ).asJava

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] = Future(
    List.empty[TextEdit].asJava
  ).asJava

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] = Future(
    List.empty[TextEdit].asJava
  ).asJava

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
    CancelTokens { _ => referencesResult(params).flatMap(_.locations).asJava }

  // Triggers a cascade compilation and tries to find new references to a given symbol.
  // It's not possible to stream reference results so if we find new symbols we notify the
  // user to run references again to see updated results.
  protected def compileAndLookForNewReferences(
      params: ReferenceParams,
      result: List[ReferencesResult],
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val old = path.toInputFromBuffers(buffers)
    compilations.cascadeCompileFiles(Seq(path)).foreach { _ =>
      val newBuffer = path.toInputFromBuffers(buffers)
      val newParams: Option[ReferenceParams] =
        if (newBuffer.text == old.text) Some(params)
        else {
          val edit = TokenEditDistance(old, newBuffer, trees)
          edit
            .getOrElse(TokenEditDistance.NoMatch)
            .toRevised(
              params.getPosition.getLine,
              params.getPosition.getCharacter,
            )
            .foldResult(
              pos => {
                params.getPosition.setLine(pos.startLine)
                params.getPosition.setCharacter(pos.startColumn)
                Some(params)
              },
              () => Some(params),
              () => None,
            )
        }
      newParams match {
        case None =>
        case Some(p) =>
          val newResult = referencesProvider.references(p)
          val diff = newResult
            .flatMap(_.locations)
            .length - result.flatMap(_.locations).length
          val diffSyms: Set[String] =
            newResult.map(_.symbol).toSet -- result.map(_.symbol).toSet
          if (diffSyms.nonEmpty && diff > 0) {
            import scala.meta.internal.semanticdb.Scala._
            val names =
              diffSyms.map(sym => s"'${sym.desc.name.value}'").mkString(" and ")
            val message =
              s"Found new symbol references for $names, try running again."
            scribe.info(message)
            statusBar
              .addMessage(clientConfig.icons.info + message)
          }
      }
    }
  }

  def referencesResult(params: ReferenceParams): List[ReferencesResult] = {
    val timer = new Timer(time)
    val results: List[ReferencesResult] = referencesProvider.references(params)
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
    if (results.nonEmpty) {
      compileAndLookForNewReferences(params, results)
    }
    results
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

  override def codeLens(
      params: CodeLensParams
  ): CompletableFuture[util.List[CodeLens]] =
    CancelTokens.future { _ =>
      buildServerPromise.future.map { _ =>
        timerProvider.timedThunk(
          "code lens generation",
          thresholdMillis = 1.second.toMillis,
        ) {
          val path = params.getTextDocument.getUri.toAbsolutePath
          codeLensProvider.findLenses(path).toList.asJava
        }
      }
    }

  override def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] = {
    CancelTokens.future { _ =>
      val path = params.getTextDocument().getUri().toAbsolutePath
      if (path.isScala)
        parseTrees.currentFuture.map(_ =>
          foldingRangeProvider.getRangedForScala(path)
        )
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
      val result = workspaceSymbols.search(params.getQuery, token).toList
      if (clientConfig.initialConfig.statistics.isWorkspaceSymbol) {
        scribe.info(
          s"time: found ${result.length} results for query '${params.getQuery}' in $timer"
        )
      }
      result
    }

  def workspaceSymbol(query: String): Seq[SymbolInformation] = {
    workspaceSymbols.search(query)
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

  def discoverMainClasses(
      unresolvedParams: DebugDiscoveryParams
  ): Future[b.DebugSessionParams] =
    debugProvider.runCommandDiscovery(unresolvedParams)

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

  def cancelCompile(): Future[Unit] = Future {
    compilations.cancel()
    scribe.info("compilation cancelled")
  }

  def restartCompiler(): Future[Unit] = Future { compilers.restartAll() }

  def getLocationForSymbol(symbol: String): Option[Location] =
    definitionProvider
      .fromSymbol(symbol, focusedDocument())
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
    val scalaCliPath = scalaCliDirOrFile(path)
    if (scalaCli.loaded(scalaCliPath)) Future.unit
    else scalaCli.start(scalaCliPath)
  }

  def stopScalaCli(): Future[Unit] = scalaCli.stop()

  def copyWorksheetOutput(
      worksheetPath: AbsolutePath
  ): CompletableFuture[Object] = {
    val output = worksheetProvider.copyWorksheetOutput(worksheetPath)
    if (output.nonEmpty) {
      Future(output).asJavaObject
    } else {
      languageClient.showMessage(Messages.Worksheets.unableToExport)
      Future.successful(()).asJavaObject
    }
  }

  def analyzeStackTrace(content: String): Option[ExecuteCommandParams] =
    stacktraceAnalyzer.analyzeCommand(content)

  def debugDiscovery(params: DebugDiscoveryParams): Future[DebugSession] =
    debugProvider
      .debugDiscovery(params)
      .flatMap(debugProvider.asSession)

  def findBuildTargetByDisplayName(target: String): Option[b.BuildTarget] =
    buildTargets.findByDisplayName(target)

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

  def supportsBuildTarget(
      target: b.BuildTargetIdentifier
  ): Option[b.BuildTarget] = buildTargets.info(target)

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

  def willRenameFile(
      oldPath: AbsolutePath,
      newPath: AbsolutePath,
  ): Future[WorkspaceEdit] =
    packageProvider.willMovePath(oldPath, newPath)

  def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): Future[List[Location]] = findTextInJars.find(params)

  protected def onBuildTargetChanges(
      params: b.DidChangeBuildTarget
  ): Unit = {
    // Make sure that no compilation is running, if it is it might not get completed properly
    compilations.cancel()
    val scalaCliServers = scalaCli.servers
    val groupedByServer = params.getChanges.asScala.groupBy { change =>
      val connOpt = buildTargets.buildServerOf(change.getTarget)
      connOpt.flatMap(conn => scalaCliServers.find(_ == conn))
    }
    val scalaCliAffectedServers = groupedByServer.collect {
      case (Some(server), _) => server
    }

    scalaCliAffectedServers.map { server =>
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
  }

  val scalaCli: ScalaCliServers = register(
    new ScalaCliServers(
      () => compilers,
      compilations,
      () => statusBar,
      buffers,
      () => indexer.profiledIndexWorkspace(() => ()),
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

  protected val indexer: Indexer = Indexer(
    () => workspaceReload,
    workspaceCheck,
    languageClient,
    () => bspSession,
    executionContext,
    tables,
    () => statusBar,
    timerProvider,
    () => scalafixProvider,
    () => indexingPromise,
    () => indexerBuildTools,
    clientConfig,
    definitionIndex,
    () => referencesProvider,
    () => workspaceSymbols,
    buildTargets,
    () => interactiveSemanticdbs,
    () => buildClient,
    () => semanticDBIndexer,
    () => treeView,
    () => worksheetProvider,
    () => symbolSearch,
    fileWatcher,
    focusedDocument,
    focusedDocumentBuildTarget,
    buildTargetClasses,
    () => userConfig,
    sh,
    symbolDocs,
    scalaVersionSelector,
    sourceMapper,
    folder,
  )

  protected def onWorksheetChanged(
      paths: Seq[AbsolutePath]
  ): Future[Unit] = {
    paths
      .find { path =>
        if (clientConfig.isDidFocusProvider || focusedDocument().isDefined) {
          focusedDocument().contains(path) &&
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
        semanticdbs.textDocument(source).documentIncludingStale
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
          if (occ.role.isDefinition && !definitionOnly) {
            val refParams = new ReferenceParams(
              positionParams.getTextDocument(),
              positionParams.getPosition(),
              new ReferenceContext(false),
            )
            val results = referencesResult(refParams)
            if (results.flatMap(_.locations).isEmpty) {
              // Fallback again to the original behavior that returns
              // the definition location itself if no reference locations found,
              // for avoiding the confusing messages like "No definition found ..."
              definitionResult(positionParams, token)
            } else {
              Future.successful(
                DefinitionResult(
                  locations = results.flatMap(_.locations).asJava,
                  symbol = results.head.symbol,
                  definition = None,
                  semanticdb = None,
                )
              )
            }
          } else {
            definitionResult(positionParams, token)
          }
        case None =>
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
          reports.incognito.create(
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

  protected def scalaCliDirOrFile(path: AbsolutePath): AbsolutePath =
    path.parent

  protected def isMillBuildSc(path: AbsolutePath): Boolean = false

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]]

  def resetWorkspace(): Future[Unit] = Future {
    tables.cleanAll()
    // maybe we should reset scala-cli servers somehow
  }

  def getTastyForURI(uri: URI): Future[Either[String, String]] =
    fileDecoderProvider.getTastyForURI(uri)

  def workspaceCheck(): Unit = doctor.check(headDoctor)

}
