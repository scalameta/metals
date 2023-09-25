package scala.meta.internal.metals

import java.net.URI
import java.nio.file._
import java.sql.Connection
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
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.bsp.BspConfigGenerator
import scala.meta.internal.bsp.BspConnector
import scala.meta.internal.bsp.BspServers
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildToolSelector
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.WorkspaceReload
import scala.meta.internal.decorations.SyntheticsDecorationProvider
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.Supermethods
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.ammonite.Ammonite
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
import scala.meta.internal.metals.formatting.OnTypeFormattingProvider
import scala.meta.internal.metals.formatting.RangeFormattingProvider
import scala.meta.internal.metals.newScalaFile.NewFileProvider
import scala.meta.internal.metals.scalacli.ScalaCli
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
import scala.meta.internal.semver.SemVer
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
class MetalsLspService(
    ec: ExecutionContextExecutorService,
    sh: ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    languageClient: ConfiguredLanguageClient,
    initializeParams: InitializeParams,
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    statusBar: StatusBar,
    focusedDocument: () => Option[AbsolutePath],
    shellRunner: ShellRunner,
    timerProvider: TimerProvider,
    initTreeView: () => Unit,
    val folder: AbsolutePath,
    folderVisibleName: Option[String],
    headDoctor: HeadDoctor,
) extends Cancelable
    with TextDocumentService {
  import serverInputs._

  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)

  def getVisibleName: String = folderVisibleName.getOrElse(folder.toString())

  private val cancelables = new MutableCancelable()
  val isCancelled = new AtomicBoolean(false)

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

  private implicit val executionContext: ExecutionContextExecutorService = ec

  private val embedded: Embedded = register(
    new Embedded(
      statusBar
    )
  )

  val tables: Tables = register(new Tables(folder, time))

  implicit val reports: StdReportContext = new StdReportContext(
    folder.toNIO,
    ReportLevel.fromString(MetalsServerConfig.default.loglevel),
  )

  val folderReportsZippper: FolderReportsZippper =
    FolderReportsZippper(doctor.getTargetsInfoForReports, reports)

  private val buildTools: BuildTools = new BuildTools(
    folder,
    bspGlobalDirectories,
    userConfig,
    () => tables.buildServers.selectedServer().nonEmpty,
  )

  private val optJavaHome =
    JdkSources.defaultJavaHome(userConfig().javaHome).headOption
  private val maybeJdkVersion: Option[JdkVersion] =
    JdkVersion.maybeJdkVersionFromJavaHome(optJavaHome)

  private val fingerprints = new MutableMd5Fingerprints
  private val mtags = new Mtags
  private val focusedDocumentBuildTarget =
    new AtomicReference[b.BuildTargetIdentifier]()
  private val definitionIndex = newSymbolIndex()
  private val symbolDocs = new Docstrings(definitionIndex)
  var bspSession: Option[BspSession] =
    Option.empty[BspSession]
  private val savedFiles = new ActiveFiles(time)
  private val recentlyOpenedFiles = new ActiveFiles(time)
  val isImportInProcess = new AtomicBoolean(false)
  var excludedPackageHandler: ExcludedPackagesHandler =
    ExcludedPackagesHandler.default

  private val mainBuildTargetsData = new TargetData
  val buildTargets: BuildTargets =
    BuildTargets.from(folder, mainBuildTargetsData, tables)

  private val buildTargetClasses =
    new BuildTargetClasses(buildTargets)

  private val sourceMapper = SourceMapper(
    buildTargets,
    buffers,
  )

  private val scalaVersionSelector = new ScalaVersionSelector(
    userConfig,
    buildTargets,
  )
  private val remote = new RemoteLanguageServer(
    () => folder,
    userConfig,
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
  private val fileWatcher = register(
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
  var buildServerPromise: Promise[Unit] = Promise[Unit]()
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

  private val onBuildChanged =
    BatchedFunction.fromFuture[AbsolutePath, BuildChange](
      onBuildChangedUnbatched,
      "onBuildChanged",
    )

  val pauseables: Pauseable = Pauseable.fromPausables(
    onBuildChanged ::
      parseTrees ::
      compilations.pauseables
  )

  private val trees = new Trees(buffers, scalaVersionSelector)

  private val documentSymbolProvider = new DocumentSymbolProvider(
    trees,
    initializeParams.supportsHierarchicalDocumentSymbols,
  )

  private val onTypeFormattingProvider =
    new OnTypeFormattingProvider(buffers, trees, userConfig)
  private val rangeFormattingProvider =
    new RangeFormattingProvider(buffers, trees, userConfig)

  private val foldingRangeProvider = new FoldingRangeProvider(
    trees,
    buffers,
    foldOnlyLines = initializeParams.foldOnlyLines,
  )

  private val bloopInstall: BloopInstall = new BloopInstall(
    folder,
    languageClient,
    buildTools,
    tables,
    shellRunner,
  )

  private val bspConfigGenerator: BspConfigGenerator = new BspConfigGenerator(
    folder,
    languageClient,
    shellRunner,
    statusBar,
  )

  private val diagnostics: Diagnostics = new Diagnostics(
    buffers,
    languageClient,
    clientConfig.initialConfig.statistics,
    Option(folder),
    trees,
  )

  private val warnings: Warnings = new Warnings(
    folder,
    buildTargets,
    statusBar,
    clientConfig.icons,
    buildTools,
    compilations.isCurrentlyCompiling,
  )

  private val fileSystemSemanticdbs: FileSystemSemanticdbs =
    new FileSystemSemanticdbs(
      buildTargets,
      charset,
      folder,
      fingerprints,
    )

  private val interactiveSemanticdbs: InteractiveSemanticdbs = {
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
      )
    )
  }

  private val semanticdbs: Semanticdbs = AggregateSemanticdbs(
    List(
      fileSystemSemanticdbs,
      interactiveSemanticdbs,
    )
  )

  private val bspErrorHandler: BspErrorHandler =
    new BspErrorHandler(
      languageClient,
      folder,
      restartBspServer,
      () => bspSession,
    )

  private val buildClient: ForwardingMetalsBuildClient =
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
        maybeQuickConnectToBuildServer(params)
      },
      bspErrorHandler,
    )

  private val bloopServers: BloopServers = new BloopServers(
    buildClient,
    languageClient,
    tables,
    clientConfig.initialConfig,
  )

  private val bspServers: BspServers = new BspServers(
    folder,
    charset,
    languageClient,
    buildClient,
    tables,
    bspGlobalDirectories,
    clientConfig.initialConfig,
    userConfig,
  )

  private val bspConnector: BspConnector = new BspConnector(
    bloopServers,
    bspServers,
    buildTools,
    languageClient,
    tables,
    userConfig,
    statusBar,
    bspConfigGenerator,
    () => bspSession.map(_.mainConnection),
  )

  private val workspaceSymbols: WorkspaceSymbolProvider =
    new WorkspaceSymbolProvider(
      folder,
      buildTargets,
      definitionIndex,
      saveClassFileToDisk = !clientConfig.isVirtualDocumentSupported(),
      () => excludedPackageHandler,
      classpathSearchIndexer = classpathSearchIndexer,
    )

  private val definitionProvider: DefinitionProvider = new DefinitionProvider(
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

  private val testProvider: TestSuitesProvider = new TestSuitesProvider(
    buildTargets,
    buildTargetClasses,
    trees,
    definitionIndex,
    semanticdbs,
    buffers,
    clientConfig,
    userConfig,
    languageClient,
    getVisibleName,
    folder,
  )

  private val codeLensProvider: CodeLensProvider = {
    val runTestLensProvider =
      new RunTestCodeLens(
        buildTargetClasses,
        buffers,
        buildTargets,
        clientConfig,
        userConfig,
        trees,
        folder,
      )
    val goSuperLensProvider = new SuperMethodCodeLens(
      buffers,
      userConfig,
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

  private val implementationProvider: ImplementationProvider =
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

  private val supermethods: Supermethods = new Supermethods(
    languageClient,
    definitionProvider,
    implementationProvider,
  )

  private val referencesProvider: ReferenceProvider = new ReferenceProvider(
    folder,
    semanticdbs,
    buffers,
    definitionProvider,
    remote,
    trees,
    buildTargets,
  )

  private val syntheticsDecorator: SyntheticsDecorationProvider =
    new SyntheticsDecorationProvider(
      folder,
      semanticdbs,
      buffers,
      languageClient,
      fingerprints,
      charset,
      focusedDocument,
      clientConfig,
      userConfig,
      trees,
    )

  private val semanticDBIndexer: SemanticdbIndexer = new SemanticdbIndexer(
    List(
      referencesProvider,
      implementationProvider,
      syntheticsDecorator,
      testProvider,
    ),
    buildTargets,
    folder,
  )

  private val formattingProvider: FormattingProvider = new FormattingProvider(
    folder,
    buffers,
    userConfig,
    languageClient,
    clientConfig,
    statusBar,
    clientConfig.icons,
    tables,
    buildTargets,
  )

  private val javaFormattingProvider: JavaFormattingProvider =
    new JavaFormattingProvider(
      buffers,
      userConfig,
      buildTargets,
    )

  private val callHierarchyProvider: CallHierarchyProvider =
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

  private val javaHighlightProvider: JavaDocumentHighlightProvider =
    new JavaDocumentHighlightProvider(
      definitionProvider,
      semanticdbs,
    )

  private val packageProvider: PackageProvider =
    new PackageProvider(
      buildTargets,
      trees,
      referencesProvider,
      buffers,
      definitionProvider,
    )

  private val newFileProvider: NewFileProvider = new NewFileProvider(
    folder,
    languageClient,
    packageProvider,
    focusedDocument,
    scalaVersionSelector,
  )

  private val symbolSearch: MetalsSymbolSearch = new MetalsSymbolSearch(
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
        userConfig,
        statusBar,
        diagnostics,
        embedded,
        worksheetPublisher,
        compilations,
        scalaVersionSelector,
      )
    )
  }

  private val compilers: Compilers = register(
    new Compilers(
      folder,
      clientConfig,
      userConfig,
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

  private val renameProvider: RenameProvider = new RenameProvider(
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

  private val debugProvider: DebugProvider = register(
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
      userConfig,
      testProvider,
    )
  )

  private val scalafixProvider: ScalafixProvider = ScalafixProvider(
    buffers,
    userConfig,
    folder,
    statusBar,
    compilations,
    languageClient,
    buildTargets,
    buildClient,
    interactiveSemanticdbs,
  )

  private val codeActionProvider: CodeActionProvider = new CodeActionProvider(
    compilers,
    buffers,
    buildTargets,
    scalafixProvider,
    trees,
    diagnostics,
    languageClient,
  )

  val doctor: Doctor = new Doctor(
    folder,
    buildTargets,
    diagnostics,
    languageClient,
    () => bspSession,
    () => bspConnector.resolve(),
    tables,
    clientConfig,
    mtagsResolver,
    () => userConfig().javaHome,
    maybeJdkVersion,
    getVisibleName,
    buildTools,
  )

  val gitHubIssueFolderInfo = new GitHubIssueFolderInfo(
    () => tables.buildTool.selectedBuildTool(),
    buildTargets,
    () => bspSession,
    () => bspConnector.resolve(),
    buildTools,
  )

  private val fileDecoderProvider: FileDecoderProvider =
    new FileDecoderProvider(
      folder,
      compilers,
      buildTargets,
      userConfig,
      shellRunner,
      fileSystemSemanticdbs,
      interactiveSemanticdbs,
      languageClient,
      clientConfig,
      new ClassFinder(trees),
    )

  private val workspaceReload: WorkspaceReload = new WorkspaceReload(
    folder,
    languageClient,
    tables,
  )

  private val buildToolSelector: BuildToolSelector = new BuildToolSelector(
    languageClient,
    tables,
  )

  def loadedPresentationCompilerCount(): Int =
    compilers.loadedPresentationCompilerCount()

  val treeView =
    new FolderTreeViewProvider(
      Folder(folder, folderVisibleName),
      buildTargets,
      () => buildClient.ongoingCompilations(),
      definitionIndex,
      id => compilations.compileTarget(id),
      () => bspSession.map(_.mainConnectionIsBloop).getOrElse(false),
      clientConfig.initialConfig.statistics,
    )

  private val popupChoiceReset: PopupChoiceReset = new PopupChoiceReset(
    folder,
    tables,
    languageClient,
    headDoctor.executeRefreshDoctor,
    () => slowConnectToBuildServer(forceImport = true),
    bspConnector,
    () => quickConnectToBuildServer(),
  )

  private val findTextInJars: FindTextInDependencyJars =
    new FindTextInDependencyJars(
      buildTargets,
      () => folder,
      languageClient,
      saveJarFileToDisk = !clientConfig.isVirtualDocumentSupported(),
    )

  private val ammonite: Ammonite = register {
    val amm = new Ammonite(
      buffers,
      compilers,
      compilations,
      statusBar,
      diagnostics,
      tables,
      languageClient,
      buildClient,
      userConfig,
      () => indexer.profiledIndexWorkspace(() => ()),
      () => folder,
      focusedDocument,
      clientConfig.initialConfig,
      scalaVersionSelector,
      parseTreesAndPublishDiags,
    )
    buildTargets.addData(amm.buildTargetsData)
    amm
  }

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

  def loadFingerPrints(): Unit = {
    // load fingerprints from last execution
    fingerprints.addAll(tables.fingerprints.load())
  }

  def allActionCommandsIds = codeActionProvider.allActionCommandsIds

  def executeCodeActionCommand(
      params: l.ExecuteCommandParams,
      token: CancelToken,
  ): Future[Unit] = codeActionProvider.executeCommands(params, token)

  def registerNiceToHaveFilePatterns(): Unit = {
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
              clientConfig.globSyntax.registrationOptions(
                this.folder
              ),
            )
          ).asJava
        )
      )
    }
  }

  val isInitialized = new AtomicBoolean(false)

  def connectTables(): Connection = tables.connect()

  def initialized(): Future[Unit] =
    for {
      _ <- maybeSetupScalaCli()
      _ <-
        Future
          .sequence(
            List[Future[Unit]](
              Future(buildTools.initialize()),
              quickConnectToBuildServer().ignoreValue,
              slowConnectToBuildServer(forceImport = false).ignoreValue,
              Future(workspaceSymbols.indexClasspath()),
              Future(formattingProvider.load()),
            )
          )
    } yield ()

  def onShutdown(): Unit = {
    tables.fingerprints.save(fingerprints.getAllFingerprints())
    cancel()
  }

  def onUserConfigUpdate(old: UserConfiguration): Future[Unit] = {
    if (userConfig().excludedPackages != old.excludedPackages) {
      excludedPackageHandler = ExcludedPackagesHandler.fromUserConfiguration(
        userConfig().excludedPackages.getOrElse(Nil)
      )
      workspaceSymbols.indexClasspath()
    }

    userConfig().fallbackScalaVersion.foreach { version =>
      if (!ScalaVersions.isSupportedAtReleaseMomentScalaVersion(version)) {
        val params =
          Messages.UnsupportedScalaVersion.fallbackScalaVersionParams(
            version
          )
        languageClient.showMessage(params)
      }
    }

    if (userConfig().symbolPrefixes != old.symbolPrefixes) {
      compilers.restartAll()
    }

    val resetDecorations =
      if (
        userConfig().showImplicitArguments != old.showImplicitArguments ||
        userConfig().showImplicitConversionsAndClasses != old.showImplicitConversionsAndClasses ||
        userConfig().showInferredType != old.showInferredType
      ) {
        buildServerPromise.future.flatMap { _ =>
          syntheticsDecorator.refresh()
        }
      } else {
        Future.successful(())
      }

    val restartBuildServer = bspSession
      .map { session =>
        if (session.main.isBloop) {
          bloopServers
            .ensureDesiredVersion(
              userConfig().currentBloopVersion,
              session.version,
              userConfig().bloopVersion.nonEmpty,
              old.bloopVersion.isDefined,
              () => autoConnectToBuildServer,
            )
            .flatMap { _ =>
              bloopServers.ensureDesiredJvmSettings(
                userConfig().bloopJvmProperties,
                userConfig().javaHome,
                () => autoConnectToBuildServer(),
              )
            }
        } else if (
          userConfig().ammoniteJvmProperties != old.ammoniteJvmProperties && buildTargets.allBuildTargetIds
            .exists(Ammonite.isAmmBuildTarget)
        ) {
          languageClient
            .showMessageRequest(Messages.AmmoniteJvmParametersChange.params())
            .asScala
            .flatMap {
              case item
                  if item == Messages.AmmoniteJvmParametersChange.restart =>
                ammonite.reload()
              case _ =>
                Future.successful(())
            }
        } else {
          Future.successful(())
        }
      }
      .getOrElse(Future.successful(()))

    Future
      .sequence(
        List(restartBuildServer, resetDecorations)
      )
      .map(_ => ())
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
    // We need both parser and semanticdb for synthetic decorations
    val publishSynthetics = for {
      _ <- Future.sequence(List(parseTrees(path), interactive))
      _ <- Future.sequence(
        List(
          syntheticsDecorator.publishSynthetics(path),
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
                publishSynthetics,
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

  def didFocus(
      uri: String
  ): CompletableFuture[DidFocusResult.Value] = {
    val path = uri.toAbsolutePath
    buildTargets
      .inverseSources(path)
      .foreach(focusedDocumentBuildTarget.set)
    // unpublish diagnostic for dependencies
    interactiveSemanticdbs.didFocus(path)
    // Don't trigger compilation on didFocus events under cascade compilation
    // because save events already trigger compile in inverse dependencies.
    if (path.isDependencySource(folder)) {
      syntheticsDecorator.publishSynthetics(path)
      CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    } else if (recentlyOpenedFiles.isRecentlyActive(path)) {
      CompletableFuture.completedFuture(DidFocusResult.RecentlyActive)
    } else {
      syntheticsDecorator.publishSynthetics(path)
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
          .flatMap { _ => syntheticsDecorator.publishSynthetics(path) }
          .ignoreValue
          .asJava
    }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    buffers.remove(path)
    compilers.didClose(path)
    trees.didClose(path)
    diagnostics.onClose(path)
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

  private def maybeAmendScalaCliBspConfig(file: AbsolutePath): Future[Unit] = {
    def isScalaCli = bspSession.exists(_.main.isScalaCLI)
    def isScalaFile =
      file.toString.isScala || file.isJava || file.isAmmoniteScript
    if (
      isScalaCli && isScalaFile &&
      buildTargets.inverseSources(file).isEmpty &&
      file.toNIO.startsWith(folder.toNIO) &&
      !ScalaCliBspScope.inScope(folder, file)
    ) {
      languageClient
        .showMessageRequest(
          FileOutOfScalaCliBspScope.askToRegenerateConfigAndRestartBsp(
            file.toNIO
          )
        )
        .asScala
        .flatMap {
          case FileOutOfScalaCliBspScope.regenerateAndRestart =>
            val buildTool = ScalaCliBuildTool(folder, folder, userConfig)
            for {
              _ <- buildTool.generateBspConfig(
                folder,
                bspConfigGenerator.runUnconditionally(buildTool, _),
                statusBar,
              )
              _ <- quickConnectToBuildServer()
            } yield ()
          case _ => Future.successful(())
        }
    } else Future.successful(())
  }

  private def didCompileTarget(report: CompileReport): Unit = {
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
    deleteEvents.map(_.getUri().toAbsolutePath).foreach(onDelete)
    onChange(changeAndCreateEvents.map(_.getUri().toAbsolutePath))
  }

  /**
   * This filter is an optimization and it is closely related to which files are
   * processed in [[didChangeWatchedFiles]]
   */
  private def fileWatchFilter(path: Path): Boolean = {
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
  private def didChangeWatchedFiles(
      event: FileWatcherEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path)
    val isScalaOrJava = path.isScalaOrJava

    event.eventType match {
      case EventType.CreateOrModify
          if path.isInBspDirectory(folder) && path.extension == "json" =>
        scribe.info(s"Detected new build tool in $path")
        quickConnectToBuildServer()
      case _ =>
    }
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
    } else if (path.isBuild) {
      onBuildChanged(List(path)).ignoreValue.asJava
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
          Future(indexer.reindexWorkspaceSources(paths)),
          compilations.compileFiles(paths),
          onBuildChanged(paths).ignoreValue,
          Future.sequence(paths.map(onBuildToolAdded)),
        ) ++ paths.map(f => Future(interactiveSemanticdbs.textDocument(f)))
      )
      .ignoreValue
  }

  private def onDelete(path: AbsolutePath): Future[Unit] = {
    Future
      .sequence(
        List(
          compilations.compileFiles(List(path)),
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
          syntheticsDecorator.addSyntheticsHover(params, hover.map(_.toLsp()))
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
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        for {
          projectRoot <- calculateOptProjectRoot().map(_.getOrElse(folder))
          res <- formattingProvider.format(path, projectRoot, token)
        } yield res
    }

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format()
      else
        onTypeFormattingProvider.format(params).asJava
    }

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        rangeFormattingProvider.format(params).asJava
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
    CancelTokens { _ => referencesResult(params).flatMap(_.locations).asJava }

  // Triggers a cascade compilation and tries to find new references to a given symbol.
  // It's not possible to stream reference results so if we find new symbols we notify the
  // user to run references again to see updated results.
  private def compileAndLookForNewReferences(
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
      compilers.semanticTokens(params, token).map { semanticTokens =>
        if (semanticTokens.getData().isEmpty()) null
        else semanticTokens
      }
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

  def restartBspServer(): Future[Boolean] = {
    def emitMessage(msg: String) = {
      languageClient.showMessage(new MessageParams(MessageType.Warning, msg))
    }
    // This is for `bloop` and `sbt`, for which `build/shutdown` doesn't shutdown the server.
    val shutdownBsp =
      bspSession match {
        case Some(session) if session.main.isBloop =>
          for {
            _ <- disconnectOldBuildServer()
          } yield bloopServers.shutdownServer()
        case Some(session) if session.main.isSbt =>
          for {
            currentBuildTool <- buildTool()
            res <- currentBuildTool match {
              case Some(sbt: SbtBuildTool) =>
                for {
                  _ <- disconnectOldBuildServer()
                  code <- sbt.shutdownBspServer(shellRunner)
                } yield code == 0
              case _ => Future.successful(false)
            }
          } yield res
        case s => Future.successful(s.nonEmpty)
      }

    for {
      didShutdown <- shutdownBsp
      _ = if (!didShutdown) {
        bspSession match {
          case Some(session) =>
            emitMessage(
              s"Could not shutdown ${session.main.name} server. Will try to reconnect."
            )
          case None =>
            emitMessage("No build server connected. Will try to connect.")
        }
      }
      _ <- autoConnectToBuildServer()
    } yield didShutdown
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

  private def applyEdits(uri: String, edits: List[TextEdit]) = languageClient
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

  def ammoniteStart(): Future[Unit] = ammonite.start()
  def ammoniteStop(): Future[Unit] = ammonite.stop()

  def switchBspServer(): Future[Unit] =
    for {
      isSwitched <- bspConnector.switchBuildServer(
        folder,
        () => slowConnectToBuildServer(forceImport = true),
      )
      _ <- {
        if (isSwitched) quickConnectToBuildServer()
        else Future.successful(())
      }
    } yield ()

  def resetPopupChoice(value: String): Future[Unit] =
    popupChoiceReset.reset(value)

  def interactivePopupChoiceReset(): Future[Unit] =
    popupChoiceReset.interactiveReset()

  def analyzeStackTrace(content: String): Option[ExecuteCommandParams] =
    stacktraceAnalyzer.analyzeCommand(content)

  def debugDiscovery(params: DebugDiscoveryParams): CompletableFuture[Object] =
    debugProvider
      .debugDiscovery(params)
      .flatMap(debugProvider.asSession)
      .asJavaObject

  def findBuildTargetByDisplayName(target: String): Option[b.BuildTarget] =
    buildTargets.findByDisplayName(target)

  def createDebugSession(
      target: b.BuildTargetIdentifier
  ): Future[DebugSession] =
    debugProvider.createDebugSession(target).flatMap(debugProvider.asSession)

  def findTestClassAndItsBuildTarget(
      params: DebugUnresolvedTestClassParams
  ): Future[List[(String, b.BuildTarget)]] =
    debugProvider.findTestClassAndItsBuildTarget(params)

  def startTestSuiteForResolved(
      targets: List[(String, b.BuildTarget)],
      params: DebugUnresolvedTestClassParams,
  ): Future[DebugSession] = debugProvider
    .buildTestClassParams(targets, params)
    .flatMap(debugProvider.asSession)

  def findMainClassAndItsBuildTarget(
      params: DebugUnresolvedMainClassParams
  ): Future[List[(b.ScalaMainClass, b.BuildTarget)]] =
    debugProvider.findMainClassAndItsBuildTarget(params)

  def startMainClass(
      foundClasses: List[(b.ScalaMainClass, b.BuildTarget)],
      params: DebugUnresolvedMainClassParams,
  ): Future[DebugSession] = debugProvider
    .buildMainClassParams(foundClasses, params)
    .flatMap(debugProvider.asSession)

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

  def generateBspConfig(): Future[Unit] = {
    val servers: List[BuildServerProvider] =
      buildTools.loadSupported().collect {
        case buildTool: BuildServerProvider => buildTool
      }

    def ensureAndConnect(
        buildTool: BuildServerProvider,
        status: BspConfigGenerationStatus,
    ): Unit =
      status match {
        case Generated =>
          tables.buildServers.chooseServer(buildTool.getBuildServerName)
          quickConnectToBuildServer().ignoreValue
        case Cancelled => ()
        case Failed(exit) =>
          exit match {
            case Left(exitCode) =>
              scribe.error(
                s"Create of .bsp failed with exit code: $exitCode"
              )
              languageClient.showMessage(
                Messages.BspProvider.genericUnableToCreateConfig
              )
            case Right(message) =>
              languageClient.showMessage(
                Messages.BspProvider.unableToCreateConfigFromMessage(
                  message
                )
              )
          }
      }

    (servers match {
      case Nil =>
        scribe.warn(Messages.BspProvider.noBuildToolFound.toString())
        languageClient.showMessage(Messages.BspProvider.noBuildToolFound)
        Future.successful(())
      case buildTool :: Nil =>
        buildTool
          .generateBspConfig(
            folder,
            args =>
              bspConfigGenerator.runUnconditionally(
                buildTool,
                args,
              ),
            statusBar,
          )
          .map(status => ensureAndConnect(buildTool, status))
      case buildTools =>
        bspConfigGenerator
          .chooseAndGenerate(buildTools)
          .map {
            case (
                  buildTool: BuildServerProvider,
                  status: BspConfigGenerationStatus,
                ) =>
              ensureAndConnect(buildTool, status)
          }
    })
  }

  private def buildTool(): Future[Option[BuildTool]] = {
    buildTools.loadSupported match {
      case Nil => Future(None)
      case buildTools =>
        for {
          Some(buildTool) <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
          if isCompatibleVersion(buildTool)
        } yield Some(buildTool)
    }
  }

  private def isCompatibleVersion(buildTool: BuildTool) =
    SemVer.isCompatibleVersion(
      buildTool.minimumVersion,
      buildTool.version,
    )

  def supportedBuildTool(): Future[Option[BuildTool]] = {
    def isCompatibleVersion(buildTool: BuildTool) = {
      val isCompatibleVersion = this.isCompatibleVersion(buildTool)
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
        if (!buildTools.isAutoConnectable()) {
          warnings.noBuildTool()
        }
        // wait for a bsp file to show up
        fileWatcher.start(Set(folder.resolve(".bsp")))
        Future(None)
      }
      case buildTools =>
        for {
          Some(buildTool) <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield isCompatibleVersion(buildTool)
    }
  }

  def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] =
    for {
      possibleBuildTool <- supportedBuildTool()
      chosenBuildServer = tables.buildServers.selectedServer()
      isBloopOrEmpty = chosenBuildServer.isEmpty || chosenBuildServer.exists(
        _ == BloopServers.name
      )
      buildChange <- possibleBuildTool match {
        case Some(buildTool) =>
          (buildTool.digest(folder), buildTool) match {
            case (None, _) =>
              scribe.warn(s"Skipping build import, no checksum.")
              Future.successful(BuildChange.None)
            case (Some(_), buildTool: ScalaCliBuildTool)
                if chosenBuildServer.isEmpty =>
              tables.buildServers.chooseServer(ScalaCliBuildTool.name)
              val scalaCliBspConfigExists =
                ScalaCliBuildTool.pathsToScalaCliBsp(folder).exists(_.isFile)
              if (scalaCliBspConfigExists) Future.successful(BuildChange.None)
              else
                buildTool
                  .generateBspConfig(
                    folder,
                    args =>
                      bspConfigGenerator.runUnconditionally(buildTool, args),
                    statusBar,
                  )
                  .flatMap(_ => quickConnectToBuildServer())
            case (Some(digest), _) if isBloopOrEmpty =>
              slowConnectToBloopServer(forceImport, buildTool, digest)
            case (Some(digest), _) =>
              indexer.reloadWorkspaceAndIndex(
                forceImport,
                buildTool,
                digest,
                importBuild,
              )
          }
        case None =>
          Future.successful(BuildChange.None)
      }
    } yield buildChange

  /**
   * If there is no auto-connectable build server and no supported build tool is found
   * we assume it's a scala-cli project.
   */
  def maybeSetupScalaCli(): Future[Unit] = {
    if (
      !buildTools.isAutoConnectable()
      && buildTools.loadSupported.isEmpty
      && folder.hasScalaFiles
    ) scalaCli.setupIDE(folder)
    else Future.successful(())
  }

  private def slowConnectToBloopServer(
      forceImport: Boolean,
      buildTool: BuildTool,
      checksum: String,
  ): Future[BuildChange] =
    for {
      result <- {
        if (forceImport)
          bloopInstall.runUnconditionally(buildTool, isImportInProcess)
        else bloopInstall.runIfApproved(buildTool, checksum, isImportInProcess)
      }
      change <- {
        if (result.isInstalled) quickConnectToBuildServer()
        else if (result.isFailed) {
          for {
            maybeProjectRoot <- calculateOptProjectRoot()
            change <-
              if (buildTools.isAutoConnectable(maybeProjectRoot)) {
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
          } yield change
        } else {
          Future.successful(BuildChange.None)
        }

      }
    } yield change

  def calculateOptProjectRoot(): Future[Option[AbsolutePath]] =
    for {
      possibleBuildTool <- buildTool()
    } yield possibleBuildTool.map(_.projectRoot).orElse(buildTools.bloopProject)

  def quickConnectToBuildServer(): Future[BuildChange] =
    for {
      optRoot <- calculateOptProjectRoot()
      change <-
        if (!buildTools.isAutoConnectable(optRoot)) {
          scribe.warn("Build server is not auto-connectable.")
          Future.successful(BuildChange.None)
        } else {
          autoConnectToBuildServer()
        }
    } yield {
      buildServerPromise.trySuccess(())
      change
    }

  private def maybeQuickConnectToBuildServer(
      params: b.DidChangeBuildTarget
  ): Unit = {
    val (ammoniteChanges, otherChanges) =
      params.getChanges.asScala.partition { change =>
        val connOpt = buildTargets.buildServerOf(change.getTarget)
        connOpt.nonEmpty && connOpt == ammonite.buildServer
      }
    val (scalaCliBuildChanges, otherChanges0) =
      otherChanges.partition { change =>
        val connOpt = buildTargets.buildServerOf(change.getTarget)
        connOpt.nonEmpty && connOpt == scalaCli.buildServer
      }

    if (ammoniteChanges.nonEmpty)
      ammonite.importBuild().onComplete {
        case Success(()) =>
        case Failure(exception) =>
          scribe.error("Error re-importing Ammonite build", exception)
      }

    if (scalaCliBuildChanges.nonEmpty)
      scalaCli
        .importBuild()
        .onComplete {
          case Success(()) =>
          case Failure(exception) =>
            scribe
              .error("Error re-importing Scala CLI build", exception)
        }

    if (otherChanges0.nonEmpty)
      quickConnectToBuildServer().onComplete {
        case Failure(e) =>
          scribe.warn("Error refreshing build", e)
        case Success(_) =>
          scribe.info("Refreshed build after change")
      }
  }

  def autoConnectToBuildServer(): Future[BuildChange] = {
    def compileAllOpenFiles: BuildChange => Future[BuildChange] = {
      case change if !change.isFailed =>
        Future
          .sequence(
            compilations
              .cascadeCompileFiles(buffers.open.toSeq)
              .ignoreValue ::
              compilers.load(buffers.open.toSeq) ::
              Nil
          )
          .map(_ => change)
      case other => Future.successful(other)
    }

    val scalaCliPath = scalaCli.path

    (for {
      _ <- disconnectOldBuildServer()
      maybeProjectRoot <- calculateOptProjectRoot()
      maybeSession <- timerProvider.timed("Connected to build server", true) {
        bspConnector.connect(
          maybeProjectRoot.getOrElse(folder),
          folder,
          userConfig(),
          shellRunner,
        )
      }
      result <- maybeSession match {
        case Some(session) =>
          val result = connectToNewBuildServer(session)
          session.mainConnection.onReconnection { newMainConn =>
            val updSession = session.copy(main = newMainConn)
            connectToNewBuildServer(updSession)
              .flatMap(compileAllOpenFiles)
              .ignoreValue
          }
          result
        case None =>
          Future.successful(BuildChange.None)
      }
      _ <- scalaCliPath
        .collectFirst {
          case path if (!conflictsWithMainBsp(path.toNIO)) =>
            scalaCli.start(path)
        }
        .getOrElse(Future.successful(()))
      _ = initTreeView()
    } yield result)
      .recover { case NonFatal(e) =>
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
      .flatMap(compileAllOpenFiles)
  }

  def disconnectOldBuildServer(): Future[Unit] = {
    diagnostics.reset()
    bspSession.foreach(connection =>
      scribe.info(s"Disconnecting from ${connection.main.name} session...")
    )

    for {
      _ <- scalaCli.stop()
      _ <- bspSession match {
        case None => Future.successful(())
        case Some(session) =>
          bspSession = None
          mainBuildTargetsData.resetConnections(List.empty)
          session.shutdown()
      }
    } yield ()
  }

  private def importBuild(session: BspSession) = {
    compilers.cancel()
    val importedBuilds0 = timerProvider.timed("Imported build") {
      session.importBuilds()
    }
    for {
      bspBuilds <- statusBar.trackFuture("Importing build", importedBuilds0)
      _ = {
        val idToConnection = bspBuilds.flatMap { bspBuild =>
          val targets =
            bspBuild.build.workspaceBuildTargets.getTargets().asScala
          targets.map(t => (t.getId(), bspBuild.connection))
        }
        mainBuildTargetsData.resetConnections(idToConnection)
      }
    } yield ()
  }

  private def connectToNewBuildServer(
      session: BspSession
  ): Future[BuildChange] = {
    scribe.info(
      s"Connected to Build server: ${session.main.name} v${session.version}"
    )
    cancelables.add(session)
    bspSession = Some(session)
    for {
      _ <- importBuild(session)
      _ <- indexer.profiledIndexWorkspace(runDoctorCheck)
      _ = if (session.main.isBloop) checkRunningBloopVersion(session.version)
    } yield {
      BuildChange.Reconnected
    }
  }

  val scalaCli: ScalaCli = register(
    new ScalaCli(
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
      userConfig,
      parseTreesAndPublishDiags,
    )
  )
  buildTargets.addData(scalaCli.buildTargetsData)

  private val indexer = Indexer(
    () => workspaceReload,
    runDoctorCheck,
    languageClient,
    () => bspSession,
    executionContext,
    tables,
    () => statusBar,
    timerProvider,
    () => scalafixProvider,
    () => indexingPromise,
    () =>
      Seq(
        Indexer.BuildTool(
          "main",
          mainBuildTargetsData,
          ImportedBuild.fromList(
            bspSession.map(_.lastImportedBuild).getOrElse(Nil)
          ),
        ),
        Indexer.BuildTool(
          "ammonite",
          ammonite.buildTargetsData,
          ammonite.lastImportedBuild,
        ),
        Indexer.BuildTool(
          "scala-cli",
          scalaCli.buildTargetsData,
          scalaCli.lastImportedBuild,
        ),
      ),
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
    () => buildTools,
    () => formattingProvider,
    fileWatcher,
    focusedDocument,
    focusedDocumentBuildTarget,
    buildTargetClasses,
    userConfig,
    sh,
    symbolDocs,
    scalaVersionSelector,
    sourceMapper,
    folder,
  )

  private def checkRunningBloopVersion(bspServerVersion: String) = {
    if (doctor.isUnsupportedBloopVersion()) {
      val notification = tables.dismissedNotifications.IncompatibleBloop
      if (!notification.isDismissed) {
        val messageParams = IncompatibleBloopVersion.params(
          bspServerVersion,
          BuildInfo.bloopVersion,
          isChangedInSettings = userConfig().bloopVersion != None,
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

  private def onWorksheetChanged(
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

  private def onBuildChangedUnbatched(
      paths: Seq[AbsolutePath]
  ): Future[BuildChange] = {
    val changedBuilds = paths.flatMap(buildTools.isBuildRelated)
    val buildChange = for {
      chosenBuildTool <- tables.buildTool.selectedBuildTool()
      if (changedBuilds.contains(chosenBuildTool))
    } yield slowConnectToBuildServer(forceImport = false)
    buildChange.getOrElse(Future.successful(BuildChange.None))
  }

  private def onBuildToolAdded(
      path: AbsolutePath
  ): Future[BuildChange] = {
    val supportedBuildTools = buildTools.loadSupported()
    val maybeBuildChange = for {
      currentBuildToolName <- tables.buildTool.selectedBuildTool()
      currentBuildTool <- supportedBuildTools.find(
        _.executableName == currentBuildToolName
      )
      addedBuildName <- buildTools.isBuildRelated(path)
      if (buildTools.newBuildTool(addedBuildName))
      if (addedBuildName != currentBuildToolName)
      newBuildTool <- supportedBuildTools.find(
        _.executableName == addedBuildName
      )
    } yield {
      buildToolSelector
        .onNewBuildToolAdded(newBuildTool, currentBuildTool)
        .flatMap { switch =>
          if (switch) slowConnectToBuildServer(forceImport = false)
          else Future.successful(BuildChange.None)
        }
    }
    maybeBuildChange.getOrElse(Future.successful(BuildChange.None))
  }

  /**
   * Returns the the definition location or reference locations of a symbol at a
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

  private def newSymbolIndex(): OnDemandSymbolIndex = {
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

  private def isMillBuildSc(path: AbsolutePath): Boolean =
    path.toNIO.getFileName.toString == "build.sc" &&
      // for now, this only checks for build.sc, but this could be made more strict in the future
      // (require ./mill or ./.mill-version)
      buildTools.isMill

  /**
   * Returns the absolute path or directory that ScalaCLI imports as ScalaCLI
   * scripts. By default, ScalaCLI tries to import the entire directory as
   * ScalaCLI scripts. However, we have to ensure that there are no clashes with
   * other existing sourceItems see:
   * https://github.com/scalameta/metals/issues/4447
   *
   * @param path
   *   the absolute path of the ScalaCLI script to import
   */
  private def scalaCliDirOrFile(path: AbsolutePath): AbsolutePath = {
    val dir = path.parent
    val nioDir = dir.toNIO
    if (conflictsWithMainBsp(nioDir)) path else dir
  }

  private def conflictsWithMainBsp(nioDir: Path) =
    buildTargets.sourceItems.filter(_.exists).exists { item =>
      val nioItem = item.toNIO
      nioDir.startsWith(nioItem) || nioItem.startsWith(nioDir)
    }

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    val scalaCliPath = scalaCliDirOrFile(path)
    if (
      !path.isAmmoniteScript ||
      !buildTargets.inverseSources(path).isEmpty ||
      ammonite.loaded(path) ||
      scalaCli.loaded(scalaCliPath) ||
      isMillBuildSc(path)
    )
      None
    else {
      def doImportScalaCli(): Future[Unit] =
        scalaCli
          .start(scalaCliPath)
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedScalaCli
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Scala CLI project $scalaCliPath", e)
          }
      def doImportAmmonite(): Future[Unit] =
        ammonite
          .start(Some(path))
          .map { _ =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedAmmonite
            )
          }
          .recover { e =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Ammonite script $path", e)
          }

      val autoImportAmmonite =
        tables.dismissedNotifications.AmmoniteImportAuto.isDismissed
      val autoImportScalaCli =
        tables.dismissedNotifications.ScalaCliImportAuto.isDismissed

      def askAutoImport(notification: DismissedNotifications#Notification) =
        languageClient
          .showMessageRequest(Messages.ImportAllScripts.params())
          .asScala
          .onComplete {
            case Failure(e) =>
              scribe.warn("Error requesting automatic Scala scripts import", e)
            case Success(null) =>
              scribe.debug("Automatic Scala scripts import cancelled by user")
            case Success(resp) =>
              resp.getTitle match {
                case Messages.ImportAllScripts.importAll =>
                  notification.dismissForever()
                case _ =>
              }
          }

      val futureRes =
        if (autoImportAmmonite) {
          doImportAmmonite()
        } else if (autoImportScalaCli) {
          doImportScalaCli()
        } else {
          languageClient
            .showMessageRequest(Messages.ImportScalaScript.params())
            .asScala
            .flatMap { response =>
              if (response != null)
                response.getTitle match {
                  case Messages.ImportScalaScript.doImportAmmonite =>
                    askAutoImport(
                      tables.dismissedNotifications.AmmoniteImportAuto
                    )
                    doImportAmmonite()
                  case Messages.ImportScalaScript.doImportScalaCli =>
                    askAutoImport(
                      tables.dismissedNotifications.ScalaCliImportAuto
                    )
                    doImportScalaCli()
                  case _ => Future.unit
                }
              else {
                Future.unit
              }
            }
            .recover { e =>
              scribe.warn("Error requesting Scala script import", e)
            }
        }
      Some(futureRes)
    }
  }

  private def clearBloopDir(folder: AbsolutePath): Unit = {
    try BloopDir.clear(folder)
    catch {
      case e: Throwable =>
        languageClient.showMessage(Messages.ResetWorkspaceFailed)
        scribe.error("Error while deleting directories inside .bloop", e)
    }
  }

  def resetWorkspace(): Future[Unit] =
    for {
      maybeProjectRoot <- calculateOptProjectRoot()
      _ <- disconnectOldBuildServer()
      _ = maybeProjectRoot match {
        case Some(path) if buildTools.isBloop(path) =>
          bloopServers.shutdownServer()
          clearBloopDir(path)
        case _ =>
      }
      _ = tables.cleanAll()
      _ <- autoConnectToBuildServer().map(_ => ())
    } yield ()

  def getTastyForURI(uri: URI): Future[Either[String, String]] =
    fileDecoderProvider.getTastyForURI(uri)

  def runDoctorCheck(): Unit = doctor.check(headDoctor)

}
