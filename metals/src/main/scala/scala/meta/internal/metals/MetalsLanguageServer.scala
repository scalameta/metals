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

import scala.annotation.nowarn
import scala.collection.immutable.Nil
import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.Duration
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
import scala.meta.internal.builds.BloopInstall
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildToolSelector
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.builds.WorkspaceReload
import scala.meta.internal.decorations.SyntheticsDecorationProvider
import scala.meta.internal.implementation.ImplementationProvider
import scala.meta.internal.implementation.Supermethods
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages.AmmoniteJvmParametersChange
import scala.meta.internal.metals.Messages.IncompatibleBloopVersion
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.metals.callHierarchy.CallHierarchyProvider
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.clients.language.DelegatingLanguageClient
import scala.meta.internal.metals.clients.language.ForwardingMetalsBuildClient
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.codeactions.CodeActionProvider
import scala.meta.internal.metals.codelenses.RunTestCodeLens
import scala.meta.internal.metals.codelenses.SuperMethodCodeLens
import scala.meta.internal.metals.codelenses.WorksheetCodeLens
import scala.meta.internal.metals.debug.BuildTargetClasses
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.doctor.Doctor
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles._
import scala.meta.internal.metals.formatting.OnTypeFormattingProvider
import scala.meta.internal.metals.formatting.RangeFormattingProvider
import scala.meta.internal.metals.logging.LanguageClientLogger
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.metals.newScalaFile.NewFileProvider
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.testProvider.TestSuitesProvider
import scala.meta.internal.metals.watcher.FileWatcher
import scala.meta.internal.metals.watcher.FileWatcherEvent
import scala.meta.internal.metals.watcher.FileWatcherEvent.EventType
import scala.meta.internal.mtags._
import scala.meta.internal.parsing.ClassFinder
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
import scala.meta.parsers.ParseException
import scala.meta.pc.CancelToken
import scala.meta.tokenizers.TokenizeException

import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j.ExecuteCommandParams
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
    isReliableFileWatcher: Boolean = true,
    mtagsResolver: MtagsResolver = MtagsResolver.default(),
    onStartCompilation: () => Unit = () => (),
    classpathSearchIndexer: ClasspathSearch.Indexer =
      ClasspathSearch.Indexer.default,
) extends Cancelable {
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)
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

  def cancelAll(): Unit = {
    cancel()
    Cancelable.cancelAll(
      List(
        Cancelable(() => ec.shutdown()),
        Cancelable(() => sh.shutdown()),
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
  var bspSession: Option[BspSession] =
    Option.empty[BspSession]
  private val savedFiles = new ActiveFiles(time)
  private val recentlyOpenedFiles = new ActiveFiles(time)
  private val recentlyFocusedFiles = new ActiveFiles(time)
  private val languageClient = new DelegatingLanguageClient(NoopLanguageClient)
  val isImportInProcess = new AtomicBoolean(false)
  @volatile
  var userConfig: UserConfiguration = UserConfiguration()
  var excludedPackageHandler: ExcludedPackagesHandler =
    ExcludedPackagesHandler.default

  var ammonite: Ammonite = _
  private val mainBuildTargetsData = new TargetData
  val buildTargets: BuildTargets = new BuildTargets()
  buildTargets.addData(mainBuildTargetsData)
  private val buildTargetClasses =
    new BuildTargetClasses(buildTargets)
  private var doctor: Doctor = _

  private val scalaVersionSelector = new ScalaVersionSelector(
    () => userConfig,
    buildTargets,
  )
  private val remote = new RemoteLanguageServer(
    () => workspace,
    () => userConfig,
    initialConfig,
    buffers,
    buildTargets,
  )

  val compilations: Compilations = new Compilations(
    buildTargets,
    buildTargetClasses,
    () => workspace,
    languageClient,
    () => testProvider.refreshTestSuites(),
    () => {
      if (clientConfig.isDoctorVisibilityProvider())
        doctor.executeRefreshDoctor()
      else ()
    },
    buildTarget => focusedDocumentBuildTarget.get() == buildTarget,
    worksheets => onWorksheetChanged(worksheets),
    onStartCompilation,
  )
  private val fileWatcher = register(
    new FileWatcher(
      initialConfig,
      () => workspace,
      buildTargets,
      fileWatchFilter,
      params => didChangeWatchedFiles(params),
    )
  )
  val indexingPromise: Promise[Unit] = Promise[Unit]()
  var buildServerPromise: Promise[Unit] = Promise[Unit]()
  val parseTrees = new BatchedFunction[AbsolutePath, Unit](paths =>
    CancelableFuture(
      buildServerPromise.future
        .flatMap(_ => parseTreesAndPublishDiags(paths))
        .ignoreValue,
      Cancelable.empty,
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
  private val timerProvider: TimerProvider = new TimerProvider(time)
  private val trees = new Trees(buffers, scalaVersionSelector)
  private val documentSymbolProvider = new DocumentSymbolProvider(trees)
  private val onTypeFormattingProvider =
    new OnTypeFormattingProvider(buffers, trees, () => userConfig)
  private val rangeFormattingProvider =
    new RangeFormattingProvider(buffers, trees, () => userConfig)
  private val classFinder = new ClassFinder(trees)
  private val foldingRangeProvider = new FoldingRangeProvider(trees, buffers)
  // These can't be instantiated until we know the workspace root directory.
  private var shellRunner: ShellRunner = _
  private var bloopInstall: BloopInstall = _
  private var bspConfigGenerator: BspConfigGenerator = _
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
  private var bspConnector: BspConnector = _
  private var codeLensProvider: CodeLensProvider = _
  private var supermethods: Supermethods = _
  private var codeActionProvider: CodeActionProvider = _
  private var definitionProvider: DefinitionProvider = _
  private var semanticDBIndexer: SemanticdbIndexer = _
  private var implementationProvider: ImplementationProvider = _
  private var renameProvider: RenameProvider = _
  private var formattingProvider: FormattingProvider = _
  private var javaFormattingProvider: JavaFormattingProvider = _
  private var syntheticsDecorator: SyntheticsDecorationProvider = _
  private var initializeParams: Option[InitializeParams] = None
  private var referencesProvider: ReferenceProvider = _
  private var callHierarchyProvider: CallHierarchyProvider = _
  private var workspaceSymbols: WorkspaceSymbolProvider = _
  private val packageProvider: PackageProvider =
    new PackageProvider(buildTargets)
  private var newFileProvider: NewFileProvider = _
  private var debugProvider: DebugProvider = _
  private var symbolSearch: MetalsSymbolSearch = _
  private var compilers: Compilers = _
  private var scalafixProvider: ScalafixProvider = _
  private var fileDecoderProvider: FileDecoderProvider = _
  private var testProvider: TestSuitesProvider = _
  private var workspaceReload: WorkspaceReload = _
  private var buildToolSelector: BuildToolSelector = _

  private val sourceMapper = SourceMapper(
    buildTargets,
    buffers,
    () => workspace,
  )

  def loadedPresentationCompilerCount(): Int =
    compilers.loadedPresentationCompilerCount()

  var tables: Tables = _
  var statusBar: StatusBar = _
  private var embedded: Embedded = _
  var httpServer: Option[MetalsHttpServer] = None
  var treeView: TreeViewProvider = NoopTreeViewProvider
  var worksheetProvider: WorksheetProvider = _
  var popupChoiceReset: PopupChoiceReset = _
  var stacktraceAnalyzer: StacktraceAnalyzer = _
  var findTextInJars: FindTextInDependencyJars = _

  private val clientConfig: ClientConfiguration = ClientConfiguration(
    initialConfig
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

  def connectToLanguageClient(client: MetalsLanguageClient): Unit = {
    languageClient.underlying =
      new ConfiguredLanguageClient(client, clientConfig)(ec)
    statusBar = new StatusBar(
      () => languageClient,
      time,
      progressTicks,
      clientConfig,
    )
    embedded = register(
      new Embedded(
        statusBar
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

    // NOTE: we purposefully don't check workspaceFolders here
    // since Metals technically doesn't support it. Once we implement
    // https://github.com/scalameta/metals-feature-requests/issues/87 we'll
    // have to change this.
    val root =
      Option(params.getRootUri()).orElse(Option(params.getRootPath()))

    root match {
      case None =>
        languageClient.showMessage(Messages.noRoot)
      case Some(path) =>
        workspace = path.toAbsolutePath
        MetalsLogger.setupLspLogger(workspace, redirectSystemOut)

        val clientInfo = Option(params.getClientInfo()) match {
          case Some(info) =>
            s"for client ${info.getName()} ${Option(info.getVersion).getOrElse("")}"
          case None => ""
        }

        scribe.info(
          s"Started: Metals version ${BuildInfo.metalsVersion} in workspace '$workspace' $clientInfo."
        )
        clientConfig.update(params)

        foldingRangeProvider.setFoldOnlyLines(Option(params).foldOnlyLines)
        documentSymbolProvider.setSupportsHierarchicalDocumentSymbols(
          initializeParams.supportsHierarchicalDocumentSymbols
        )
        buildTargets.setWorkspaceDirectory(workspace)
        tables = register(new Tables(workspace, time))
        buildTargets.setTables(tables)
        workspaceReload = new WorkspaceReload(
          workspace,
          languageClient,
          tables,
        )
        buildTools = new BuildTools(
          workspace,
          bspGlobalDirectories,
          () => userConfig,
          () => tables.buildServers.selectedServer().nonEmpty,
        )
        fileSystemSemanticdbs = new FileSystemSemanticdbs(
          buildTargets,
          charset,
          workspace,
          fingerprints,
        )

        val optJavaHome =
          (userConfig.javaHome orElse JdkSources.defaultJavaHome)
            .map(AbsolutePath(_))
        val maybeJdkVersion: Option[JdkVersion] =
          JdkVersion.maybeJdkVersionFromJavaHome(optJavaHome)
        val javaInteractiveSemanticdb =
          for {
            javaHome <- optJavaHome
            jdkVersion <- maybeJdkVersion
            javaSemanticDb <- JavaInteractiveSemanticdb.create(
              javaHome,
              workspace,
              buildTargets,
              jdkVersion,
            )
          } yield javaSemanticDb

        interactiveSemanticdbs = register(
          new InteractiveSemanticdbs(
            workspace,
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
        warnings = new Warnings(
          workspace,
          buildTargets,
          statusBar,
          clientConfig.icons,
          buildTools,
          compilations.isCurrentlyCompiling,
        )
        diagnostics = new Diagnostics(
          buffers,
          languageClient,
          clientConfig.initialConfig.statistics,
          Option(workspace),
          trees,
        )
        buildClient = new ForwardingMetalsBuildClient(
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
            treeView.onBuildTargetDidCompile(target)
            worksheetProvider.onBuildTargetDidCompile(target)
          },
          onBuildTargetDidChangeFunc = maybeQuickConnectToBuildServer,
        )
        shellRunner = register(
          new ShellRunner(languageClient, () => userConfig, time, statusBar)
        )
        bloopInstall = new BloopInstall(
          workspace,
          languageClient,
          buildTools,
          tables,
          shellRunner,
        )
        bspConfigGenerator = new BspConfigGenerator(
          workspace,
          languageClient,
          shellRunner,
        )
        newProjectProvider = new NewProjectProvider(
          languageClient,
          statusBar,
          clientConfig,
          shellRunner,
          clientConfig.icons,
          workspace,
        )
        bloopServers = new BloopServers(
          buildClient,
          languageClient,
          tables,
          clientConfig.initialConfig,
        )
        bspServers = new BspServers(
          workspace,
          charset,
          languageClient,
          buildClient,
          tables,
          bspGlobalDirectories,
          clientConfig.initialConfig,
        )
        buildToolSelector = new BuildToolSelector(
          languageClient,
          tables,
        )
        bspConnector = new BspConnector(
          bloopServers,
          bspServers,
          buildTools,
          languageClient,
          tables,
          () => userConfig,
          statusBar,
          bspConfigGenerator,
          () => bspSession.map(_.mainConnection),
        )
        semanticdbs = AggregateSemanticdbs(
          List(
            fileSystemSemanticdbs,
            interactiveSemanticdbs,
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
          remote,
          trees,
          buildTargets,
          scalaVersionSelector,
          saveDefFileToDisk = !clientConfig.isVirtualDocumentSupported(),
          sourceMapper,
        )
        formattingProvider = new FormattingProvider(
          workspace,
          buffers,
          () => userConfig,
          languageClient,
          clientConfig,
          statusBar,
          clientConfig.icons,
          tables,
          buildTargets,
        )
        javaFormattingProvider = new JavaFormattingProvider(
          buffers,
          () => userConfig,
          buildTargets,
        )
        newFileProvider = new NewFileProvider(
          workspace,
          languageClient,
          packageProvider,
          () => focusedDocument,
          scalaVersionSelector,
        )
        referencesProvider = new ReferenceProvider(
          workspace,
          semanticdbs,
          buffers,
          definitionProvider,
          remote,
          trees,
          buildTargets,
        )
        callHierarchyProvider = new CallHierarchyProvider(
          workspace,
          semanticdbs,
          definitionProvider,
          referencesProvider,
          clientConfig.icons,
          () => compilers,
          trees,
          buildTargets,
        )
        implementationProvider = new ImplementationProvider(
          semanticdbs,
          workspace,
          definitionIndex,
          buildTargets,
          buffers,
          definitionProvider,
          trees,
          scalaVersionSelector,
        )

        supermethods = new Supermethods(
          languageClient,
          definitionProvider,
          implementationProvider,
        )

        val runTestLensProvider =
          new RunTestCodeLens(
            buildTargetClasses,
            buffers,
            buildTargets,
            clientConfig,
            () => userConfig,
            trees,
          )

        val goSuperLensProvider = new SuperMethodCodeLens(
          buffers,
          () => userConfig,
          clientConfig,
          trees,
        )

        stacktraceAnalyzer = new StacktraceAnalyzer(
          workspace,
          buffers,
          definitionProvider,
          clientConfig.icons,
          clientConfig.commandInHtmlFormat(),
        )
        val worksheetCodeLens = new WorksheetCodeLens(clientConfig)
        testProvider = new TestSuitesProvider(
          buildTargets,
          buildTargetClasses,
          trees,
          definitionIndex,
          semanticdbs,
          buffers,
          clientConfig,
          () => userConfig,
          languageClient,
        )
        codeLensProvider = new CodeLensProvider(
          List(
            runTestLensProvider,
            goSuperLensProvider,
            worksheetCodeLens,
            testProvider,
          ),
          semanticdbs,
          stacktraceAnalyzer,
        )
        renameProvider = new RenameProvider(
          referencesProvider,
          implementationProvider,
          definitionProvider,
          workspace,
          languageClient,
          buffers,
          compilations,
          clientConfig,
          trees,
        )
        syntheticsDecorator = new SyntheticsDecorationProvider(
          workspace,
          semanticdbs,
          buffers,
          languageClient,
          fingerprints,
          charset,
          () => focusedDocument,
          clientConfig,
          () => userConfig,
          trees,
        )
        semanticDBIndexer = new SemanticdbIndexer(
          List(
            referencesProvider,
            implementationProvider,
            syntheticsDecorator,
            testProvider,
          ),
          buildTargets,
          workspace,
        )
        workspaceSymbols = new WorkspaceSymbolProvider(
          workspace,
          buildTargets,
          definitionIndex,
          saveClassFileToDisk = !clientConfig.isVirtualDocumentSupported(),
          () => excludedPackageHandler,
          classpathSearchIndexer = classpathSearchIndexer,
        )
        symbolSearch = new MetalsSymbolSearch(
          symbolDocs,
          workspaceSymbols,
          definitionProvider,
        )
        compilers = register(
          new Compilers(
            workspace,
            clientConfig,
            () => userConfig,
            buildTargets,
            buffers,
            symbolSearch,
            embedded,
            statusBar,
            sh,
            Option(params),
            () => excludedPackageHandler,
            scalaVersionSelector,
            trees,
            mtagsResolver,
            sourceMapper,
          )
        )
        debugProvider = register(
          new DebugProvider(
            workspace,
            definitionProvider,
            buildTargets,
            buildTargetClasses,
            compilations,
            languageClient,
            buildClient,
            classFinder,
            definitionIndex,
            stacktraceAnalyzer,
            clientConfig,
            semanticdbs,
            compilers,
            statusBar,
          )
        )
        scalafixProvider = ScalafixProvider(
          buffers,
          () => userConfig,
          workspace,
          statusBar,
          compilations,
          languageClient,
          buildTargets,
          buildClient,
          interactiveSemanticdbs,
        )
        codeActionProvider = new CodeActionProvider(
          compilers,
          buffers,
          buildTargets,
          scalafixProvider,
          trees,
          diagnostics,
          languageClient,
        )

        doctor = new Doctor(
          workspace,
          buildTargets,
          diagnostics,
          languageClient,
          () => bspSession,
          () => bspConnector.resolve(),
          () => httpServer,
          tables,
          clientConfig,
          mtagsResolver,
          () => userConfig.javaHome,
          maybeJdkVersion,
        )

        fileDecoderProvider = new FileDecoderProvider(
          workspace,
          compilers,
          buildTargets,
          () => userConfig,
          shellRunner,
          fileSystemSemanticdbs,
          interactiveSemanticdbs,
          languageClient,
          clientConfig,
          classFinder,
        )
        popupChoiceReset = new PopupChoiceReset(
          workspace,
          tables,
          languageClient,
          doctor,
          () => slowConnectToBuildServer(forceImport = true),
          bspConnector,
          () => quickConnectToBuildServer(),
        )

        val worksheetPublisher =
          if (clientConfig.isDecorationProvider)
            new DecorationWorksheetPublisher(
              clientConfig.isInlineDecorationProvider()
            )
          else
            new WorkspaceEditWorksheetPublisher(buffers, trees)
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
            worksheetPublisher,
            compilers,
            compilations,
            scalaVersionSelector,
          )
        )
        ammonite = register(
          new Ammonite(
            buffers,
            compilers,
            compilations,
            statusBar,
            diagnostics,
            () => tables,
            languageClient,
            buildClient,
            () => userConfig,
            () => indexer.profiledIndexWorkspace(() => ()),
            () => workspace,
            () => focusedDocument,
            clientConfig.initialConfig,
            scalaVersionSelector,
            parseTreesAndPublishDiags,
          )
        )
        buildTargets.addData(ammonite.buildTargetsData)
        if (clientConfig.isTreeViewProvider) {
          treeView = new MetalsTreeViewProvider(
            () => workspace,
            languageClient,
            buildTargets,
            () => buildClient.ongoingCompilations(),
            definitionIndex,
            clientConfig.initialConfig.statistics,
            id => compilations.compileTarget(id),
            sh,
            () => bspSession.map(_.mainConnectionIsBloop).getOrElse(false),
          )
        }
        findTextInJars = new FindTextInDependencyJars(
          buildTargets,
          () => workspace,
          languageClient,
          saveJarFileToDisk = !clientConfig.isVirtualDocumentSupported(),
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
    timerProvider
      .timed("initialize")(Future {
        setupJna()
        initializeParams = Option(params)
        updateWorkspaceDirectory(params)

        // load fingerprints from last execution
        fingerprints.addAll(tables.fingerprints.load())
        val capabilities = new ServerCapabilities()
        capabilities.setExecuteCommandProvider(
          new ExecuteCommandOptions(
            (ServerCommands.allIds ++ codeActionProvider.allActionCommandsIds).toList.asJava
          )
        )
        capabilities.setFoldingRangeProvider(true)
        capabilities.setSelectionRangeProvider(true)
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
          new SignatureHelpOptions(List("(", "[", ",").asJava)
        )
        capabilities.setCompletionProvider(
          new CompletionOptions(
            clientConfig.isCompletionItemResolve,
            List(".", "*").asJava,
          )
        )
        capabilities.setCallHierarchyProvider(true)
        capabilities.setWorkspaceSymbolProvider(true)
        capabilities.setDocumentSymbolProvider(true)
        capabilities.setDocumentFormattingProvider(true)
        if (initializeParams.supportsCodeActionLiterals) {
          capabilities.setCodeActionProvider(
            new CodeActionOptions(
              List(
                CodeActionKind.QuickFix,
                CodeActionKind.Refactor,
                CodeActionKind.SourceOrganizeImports,
              ).asJava
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

        val gson = new Gson
        val data =
          gson.toJsonTree(MetalsExperimental())
        capabilities.setExperimental(data)
        val serverInfo = new ServerInfo("Metals", BuildInfo.metalsVersion)
        new InitializeResult(capabilities, serverInfo)
      })
      .asJava
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
              clientConfig.globSyntax.registrationOptions(
                this.workspace
              ),
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
      var completeCommand: HttpServerExchange => Unit = (_) => ()
      val server = register(
        MetalsHttpServer(
          host,
          port,
          this,
          () => render(),
          e => completeCommand(e),
          () => doctor.problemsHtmlPage(url),
          (uri) => fileDecoderProvider.getTastyForURI(uri),
        )
      )
      httpServer = Some(server)
      val newClient = new MetalsHttpClient(
        workspace,
        () => url,
        languageClient.underlying,
        () => server.reload(),
        clientConfig.icons,
        time,
        sh,
        clientConfig,
      )
      render = () => newClient.renderHtml
      completeCommand = e => newClient.completeCommand(e)
      languageClient.underlying = newClient
      server.start()
      url = server.address
    }
  }

  val isInitialized = new AtomicBoolean(false)

  @nowarn("msg=parameter value params")
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
      val result = syncUserconfiguration().flatMap(_ =>
        Future
          .sequence(
            List[Future[Unit]](
              quickConnectToBuildServer().ignoreValue,
              slowConnectToBuildServer(forceImport = false).ignoreValue,
              Future(workspaceSymbols.indexClasspath()),
              Future(startHttpServer()),
              Future(formattingProvider.load()),
            )
          )
          .ignoreValue
      )
      result
    } else {
      scribe.warn("Ignoring duplicate 'initialized' notification.")
      Future.successful(())
    }
  }.recover { case NonFatal(e) =>
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
        tables.fingerprints.save(fingerprints.getAllFingerprints())
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
        Duration(3, TimeUnit.SECONDS),
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
    // In some cases like peeking definition didOpen might be followed up by close
    // and we would lose the notion of the focused document
    focusedDocument.foreach(recentlyFocusedFiles.add)
    focusedDocument = Some(path)
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

    if (path.isDependencySource(workspace)) {
      CancelTokens { _ =>
        // publish diagnostics
        interactiveSemanticdbs.didFocus(path)
        ()
      }
    } else {
      buildServerPromise.future.flatMap { _ =>
        val triggeredImportOpt =
          if (
            path.isAmmoniteScript && buildTargets.inverseSources(path).isEmpty
          )
            maybeImportScript(path)
          else
            None
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
        triggeredImportOpt.getOrElse(load())
      }.asJava
    }
  }

  @JsonNotification("metals/didFocusTextDocument")
  def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value] = {

    val uriOpt: Option[String] = params match {
      case string: String =>
        Option(string)
      case (h: String) :: Nil =>
        Option(h)
      case _ =>
        scribe.warn(
          s"Unexpected notification params received for didFocusTextDocument: $params"
        )
        None
    }

    uriOpt match {
      case Some(uri) => {
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
      case None =>
        CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
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
        parseTrees(path)
          .flatMap { _ => syntheticsDecorator.publishSynthetics(path) }
          .ignoreValue
          .asJava
    }

  @JsonNotification("textDocument/didClose")
  def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    if (focusedDocument.contains(path)) {
      focusedDocument = recentlyFocusedFiles.pollRecent()
    }
    buffers.remove(path)
    compilers.didClose(path)
    trees.didClose(path)
    diagnostics.onClose(path)
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
          renameProvider.runSave(),
          parseTrees(path),
          onChange(List(path)),
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
        targetroot <- buildTargets.targetRoots(report.getTarget)
        semanticdb = targetroot.resolve(Directories.semanticdb)
        generatedFile <- semanticdb.listRecursive
      } {
        val event = FileWatcherEvent.createOrModify(generatedFile.toNIO)
        didChangeWatchedFiles(event).get()
      }
    }
  }

  @JsonNotification("workspace/didChangeConfiguration")
  def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] = {
    val fullJson = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
    val metalsSection =
      Option(fullJson.getAsJsonObject("metals")).getOrElse(new JsonObject)

    updateConfiguration(metalsSection).asJava
  }

  private def updateConfiguration(json: JsonObject): Future[Unit] = {
    UserConfiguration.fromJson(json, clientConfig) match {
      case Left(errors) =>
        errors.foreach { error => scribe.error(s"config error: $error") }
        Future.successful(())
      case Right(newUserConfig) =>
        val old = userConfig
        userConfig = newUserConfig
        if (userConfig.excludedPackages != old.excludedPackages) {
          excludedPackageHandler =
            ExcludedPackagesHandler.fromUserConfiguration(
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

        val resetDecorations =
          if (
            userConfig.showImplicitArguments != old.showImplicitArguments ||
            userConfig.showImplicitConversionsAndClasses != old.showImplicitConversionsAndClasses ||
            userConfig.showInferredType != old.showInferredType
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
                  userConfig.currentBloopVersion,
                  session.version,
                  userConfig.bloopVersion.nonEmpty,
                  old.bloopVersion.isDefined,
                  () => autoConnectToBuildServer,
                )
                .flatMap { _ =>
                  bloopServers.ensureDesiredJvmSettings(
                    userConfig.bloopJvmProperties,
                    userConfig.javaHome,
                    () => autoConnectToBuildServer(),
                  )
                }
            } else if (
              userConfig.ammoniteJvmProperties != old.ammoniteJvmProperties && buildTargets.allBuildTargetIds
                .exists(Ammonite.isAmmBuildTarget)
            ) {
              languageClient
                .showMessageRequest(AmmoniteJvmParametersChange.params())
                .asScala
                .flatMap {
                  case item if item == AmmoniteJvmParametersChange.restart =>
                    ammonite.reload()
                  case _ =>
                    Future.successful(())
                }
            } else {
              Future.successful(())
            }
          }
          .getOrElse(Future.successful(()))
        Future.sequence(List(restartBuildServer, resetDecorations)).map(_ => ())
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
    onChange(paths).asJava
  }

  /**
   * This filter is an optimization and it is closely related to which files are processed
   * in [[didChangeWatchedFiles]]
   */
  private def fileWatchFilter(path: Path): Boolean = {
    val abs = AbsolutePath(path)
    abs.isScalaOrJava || abs.isSemanticdb || abs.isBuild ||
    abs.isInBspDirectory(workspace)
  }

  /**
   * Callback that is executed on a file change event by the file watcher.
   *
   * Note that if you are adding processing of another kind of a file,
   * be sure to include it in the [[fileWatchFilter]]
   *
   * This method is run synchronously in the FileWatcher, so it should not do anything expensive on the main thread
   */
  private def didChangeWatchedFiles(
      event: FileWatcherEvent
  ): CompletableFuture[Unit] = {
    val path = AbsolutePath(event.path)
    val isScalaOrJava = path.isScalaOrJava

    event.eventType match {
      case EventType.CreateOrModify
          if path.isInBspDirectory(workspace) && path.extension == "json" =>
        scribe.info(s"Detected new build tool in $path")
        quickConnectToBuildServer()
      case _ =>
    }
    if (isScalaOrJava && event.eventType == EventType.Delete) {
      onDelete(path).asJava
    } else if (
      isScalaOrJava &&
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
      Future {
        event.eventType match {
          case EventType.Delete =>
            semanticDBIndexer.onDelete(event.path)
          case EventType.CreateOrModify =>
            semanticDBIndexer.onChange(event.path)
          case EventType.Overflow =>
            semanticDBIndexer.onOverflow(event.path)
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
          },
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

  @nowarn("msg=parameter value position")
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
    CancelTokens.future { _ =>
      implementationProvider.implementations(position).map(_.asJava)
    }

  @JsonRequest("textDocument/hover")
  def hover(params: HoverExtParams): CompletableFuture[Hover] = {
    CancelTokens.future { token =>
      compilers
        .hover(params, token)
        .map { hover =>
          syntheticsDecorator.addSyntheticsHover(params, hover)
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

  @JsonRequest("textDocument/documentHighlight")
  def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[util.List[DocumentHighlight]] =
    CancelTokens.future { token => compilers.documentHighlight(params, token) }

  @JsonRequest("textDocument/documentSymbol")
  def documentSymbol(
      params: DocumentSymbolParams
  ): CompletableFuture[
    JEither[util.List[DocumentSymbol], util.List[SymbolInformation]]
  ] =
    CancelTokens { _ =>
      documentSymbolProvider
        .documentSymbols(params.getTextDocument().getUri().toAbsolutePath)
        .asJava
    }

  @JsonRequest("textDocument/formatting")
  def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens.future { token =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        formattingProvider.format(path, token)
    }

  @JsonRequest("textDocument/onTypeFormatting")
  def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format()
      else
        onTypeFormattingProvider.format(params).asJava
    }

  @JsonRequest("textDocument/rangeFormatting")
  def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[util.List[TextEdit]] =
    CancelTokens { _ =>
      val path = params.getTextDocument.getUri.toAbsolutePath
      if (path.isJava)
        javaFormattingProvider.format(params)
      else
        rangeFormattingProvider.format(params).asJava
    }

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

  @JsonRequest("textDocument/prepareCallHierarchy")
  def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[util.List[CallHierarchyItem]] =
    CancelTokens.future { token =>
      callHierarchyProvider.prepare(params, token).map(_.asJava)
    }

  @JsonRequest("callHierarchy/incomingCalls")
  def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[util.List[CallHierarchyIncomingCall]] =
    CancelTokens.future { token =>
      callHierarchyProvider.incomingCalls(params, token).map(_.asJava)
    }

  @JsonRequest("callHierarchy/outgoingCalls")
  def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[util.List[CallHierarchyOutgoingCall]] =
    CancelTokens.future { token =>
      callHierarchyProvider.outgoingCalls(params, token).map(_.asJava)
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
        compilers.completionItemResolve(item)
      } else {
        Future.successful(item)
      }
    }

  @JsonRequest("textDocument/signatureHelp")
  def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    CancelTokens.future { token =>
      compilers.signatureHelp(params, token)
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
      timerProvider.timedThunk(
        "code lens generation",
        thresholdMillis = 1.second.toMillis,
      ) {
        val path = params.getTextDocument.getUri.toAbsolutePath
        codeLensProvider.findLenses(path).toList.asJava
      }
    }

  @JsonRequest("textDocument/foldingRange")
  def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[util.List[FoldingRange]] = {
    CancelTokens.future { token =>
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

  @JsonRequest("textDocument/selectionRange")
  def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[util.List[SelectionRange]] = {
    CancelTokens.future { token =>
      compilers.selectionRange(params, token)
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
    params match {
      case ServerCommands.ScanWorkspaceSources() =>
        Future {
          indexer.indexWorkspaceSources(buildTargets.allWritableData)
        }.asJavaObject
      case ServerCommands.RestartBuildServer() =>
        bspSession.foreach { session =>
          if (session.main.isBloop) bloopServers.shutdownServer()
        }
        autoConnectToBuildServer().asJavaObject
      case ServerCommands.GenerateBspConfig() =>
        generateBspConfig().asJavaObject
      case ServerCommands.ImportBuild() =>
        slowConnectToBuildServer(forceImport = true).asJavaObject
      case ServerCommands.ConnectBuildServer() =>
        quickConnectToBuildServer().asJavaObject
      case ServerCommands.DisconnectBuildServer() =>
        disconnectOldBuildServer().asJavaObject
      case ServerCommands.DecodeFile(uri) =>
        fileDecoderProvider.decodedFileContents(uri).asJavaObject
      case ServerCommands.DiscoverTestSuites(params) =>
        Future {
          testProvider.discoverTests(
            Option(params.uri).map(_.toAbsolutePath)
          )
        }.asJavaObject
      case ServerCommands.RunScalafix(params) =>
        val uri = params.getTextDocument().getUri()
        scalafixProvider
          .runAllRules(
            uri.toAbsolutePath
          )
          .flatMap { edits =>
            languageClient
              .applyEdit(
                new l.ApplyWorkspaceEditParams(
                  new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
                )
              )
              .asScala
          }
          .asJavaObject
      case ServerCommands.ChooseClass(params) =>
        fileDecoderProvider
          .chooseClassFromFile(
            params.textDocument.getUri().toAbsolutePath,
            params.kind == "class",
          )
          .asJavaObject
      case ServerCommands.RunDoctor() =>
        Future {
          doctor.onVisibilityDidChange(true)
          doctor.executeRunDoctor()
        }.asJavaObject
      case ServerCommands.ListBuildTargets() =>
        Future {
          buildTargets.all.toList
            .map(_.getDisplayName())
            .sorted
            .asJava
        }.asJavaObject
      case ServerCommands.BspSwitch() =>
        (for {
          isSwitched <- bspConnector.switchBuildServer(
            workspace,
            () => slowConnectToBuildServer(forceImport = true),
          )
          _ <- {
            if (isSwitched) quickConnectToBuildServer()
            else Future.successful(())
          }
        } yield ()).asJavaObject
      case OpenBrowserCommand(url) =>
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
      case ServerCommands.GotoPosition(location) =>
        Future {
          languageClient.metalsExecuteClientCommand(
            ClientCommands.GotoLocation.toExecuteCommandParams(
              ClientCommands.WindowLocation(
                location.getUri(),
                location.getRange(),
              )
            )
          )
        }.asJavaObject

      case ServerCommands.GotoSymbol(symbol) =>
        Future {
          for {
            location <- definitionProvider
              .fromSymbol(symbol, focusedDocument)
              .asScala
              .headOption
          } {
            languageClient.metalsExecuteClientCommand(
              ClientCommands.GotoLocation.toExecuteCommandParams(
                ClientCommands.WindowLocation(
                  location.getUri(),
                  location.getRange(),
                )
              )
            )
          }
        }.asJavaObject
      case ServerCommands.GotoLog() =>
        Future {
          val log = workspace.resolve(Directories.log)
          val linesCount = log.readText.linesIterator.size
          val pos = new l.Position(linesCount, 0)
          val location = new Location(
            log.toURI.toString(),
            new l.Range(pos, pos),
          )
          languageClient.metalsExecuteClientCommand(
            ClientCommands.GotoLocation.toExecuteCommandParams(
              ClientCommands.WindowLocation(
                location.getUri(),
                location.getRange(),
              )
            )
          )
        }.asJavaObject
      case ServerCommands.StartDebugAdapter() =>
        val args = params.getArguments.asScala.toSeq
        import DebugProvider.DebugParametersJsonParsers._
        val debugSessionParams: Future[b.DebugSessionParams] = args match {
          case Seq(debugSessionParamsParser.Jsonized(params))
              if params.getData != null =>
            debugProvider
              .ensureNoWorkspaceErrors(params.getTargets.asScala.toSeq)
              .map(_ => params)

          case Seq(mainClassParamsParser.Jsonized(params))
              if params.mainClass != null =>
            debugProvider.resolveMainClassParams(params)

          case Seq(testSuitesParamsParser.Jsonized(params))
              if params.target != null && params.requestData != null =>
            debugProvider.resolveTestSelectionParams(params)

          case Seq(testClassParamsParser.Jsonized(params))
              if params.testClass != null =>
            debugProvider.resolveTestClassParams(params)

          case Seq(attachRemoteParamsParser.Jsonized(params))
              if params.hostName != null =>
            debugProvider.resolveAttachRemoteParams(params)

          case Seq(unresolvedParamsParser.Jsonized(params)) =>
            debugProvider.debugDiscovery(params)

          case _ =>
            val argExample = ServerCommands.StartDebugAdapter.arguments
            val msg = s"Invalid arguments: $args. Expecting: $argExample"
            Future.failed(new IllegalArgumentException(msg))
        }
        val session = for {
          params <- debugSessionParams
          server <- statusBar.trackFuture(
            "Starting debug server",
            debugProvider.start(
              params,
              scalaVersionSelector,
            ),
          )
        } yield {
          statusBar.addMessage("Started debug server!")
          DebugSession(server.sessionName, server.uri.toString)
        }
        session.asJavaObject

      case ServerCommands.AnalyzeStacktrace(content) =>
        Future {
          val command = stacktraceAnalyzer.analyzeCommand(content)
          command.foreach(languageClient.metalsExecuteClientCommand)
          scribe.debug(s"Executing AnalyzeStacktrace ${command}")
        }.asJavaObject

      case ServerCommands.GotoSuperMethod(textDocumentPositionParams) =>
        Future {
          val command =
            supermethods.getGoToSuperMethodCommand(textDocumentPositionParams)
          command.foreach(languageClient.metalsExecuteClientCommand)
          scribe.debug(s"Executing GoToSuperMethod ${command}")
        }.asJavaObject

      case ServerCommands.SuperMethodHierarchy(textDocumentPositionParams) =>
        scribe.debug(s"Executing SuperMethodHierarchy ${params.getCommand()}")
        supermethods
          .jumpToSelectedSuperMethod(textDocumentPositionParams)
          .asJavaObject

      case ServerCommands.ResetChoicePopup() =>
        val argsMaybe = Option(params.getArguments())
        (argsMaybe.flatMap(_.asScala.headOption) match {
          case Some(arg: JsonPrimitive) =>
            val value = arg.getAsString().replace("+", " ")
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} for choice ${value}"
            )
            popupChoiceReset.reset(value)
          case _ =>
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} in interactive mode."
            )
            popupChoiceReset.interactiveReset()
        }).asJavaObject

      case ServerCommands.ResetNotifications() =>
        Future {
          tables.dismissedNotifications.resetAll()
        }.asJavaObject

      case ServerCommands.NewScalaFile(args) =>
        val directoryURI = args.lift(0).flatten.map(new URI(_))
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        newFileProvider
          .handleFileCreation(directoryURI, name, fileType, isScala = true)
          .asJavaObject

      case ServerCommands.NewJavaFile(args) =>
        val directoryURI = args.lift(0).flatten.map(new URI(_))
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        newFileProvider
          .handleFileCreation(directoryURI, name, fileType, isScala = false)
          .asJavaObject

      case ServerCommands.StartAmmoniteBuildServer() =>
        ammonite.start().asJavaObject
      case ServerCommands.StopAmmoniteBuildServer() =>
        ammonite.stop()

      case ServerCommands.StartScalaCliServer() =>
        val f = focusedDocument match {
          case None => Future.unit
          case Some(path) =>
            val scalaCliPath = scalaCliDirOrFile(path)
            if (scalaCli.loaded(scalaCliPath)) Future.unit
            else scalaCli.start(scalaCliPath)
        }
        f.asJavaObject
      case ServerCommands.StopScalaCliServer() =>
        scalaCli.stop()

      case ServerCommands.NewScalaProject() =>
        newProjectProvider.createNewProjectFromTemplate().asJavaObject

      case ServerCommands.CopyWorksheetOutput(path) =>
        val worksheetPath = path.toAbsolutePath
        val output = worksheetProvider.copyWorksheetOutput(worksheetPath)

        if (output.nonEmpty) {
          Future(output).asJavaObject
        } else {
          languageClient.showMessage(Messages.Worksheets.unableToExport)
          Future.successful(()).asJavaObject
        }
      case actionCommand
          if codeActionProvider.allActionCommandsIds(
            actionCommand.getCommand()
          ) =>
        CancelTokens.future { token =>
          codeActionProvider.executeCommands(params, token).withObjectValue
        }
      case cmd =>
        ServerCommands.all
          .find(command => command.id == cmd.getCommand())
          .fold {
            scribe.error(s"Unknown command '$cmd'")
          } { foundCommand =>
            scribe.error(
              s"Expected '${foundCommand.arguments}', but got '${cmd.getArguments()}'"
            )
          }
        Future.successful(()).asJavaObject
    }
  }

  @JsonNotification("metals/doctorVisibilityDidChange")
  def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      doctor.onVisibilityDidChange(params.visible)
    }.asJava

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
          params.getPosition(),
        )
        .orNull
    }.asJava

  @JsonRequest("metals/findTextInDependencyJars")
  def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[util.List[Location]] = {
    findTextInJars.find(params).map(_.asJava).asJava
  }

  private def generateBspConfig(): Future[Unit] = {
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
            workspace,
            args =>
              bspConfigGenerator.runUnconditionally(
                buildTool,
                args,
              ),
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

  private def supportedBuildTool(): Future[Option[BuildTool]] = {
    def isCompatibleVersion(buildTool: BuildTool) = {
      val isCompatibleVersion = SemVer.isCompatibleVersion(
        buildTool.minimumVersion,
        buildTool.version,
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
        // wait for a bsp file to show up
        fileWatcher.start(Set(workspace.resolve(".bsp")))
        Future(None)
      }
      case buildTool :: Nil => Future(isCompatibleVersion(buildTool))
      case buildTools =>
        for {
          Some(buildTool) <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield isCompatibleVersion(buildTool)
    }
  }

  private def slowConnectToBuildServer(
      forceImport: Boolean
  ): Future[BuildChange] =
    for {
      possibleBuildTool <- supportedBuildTool
      chosenBuildServer = tables.buildServers.selectedServer()
      isBloopOrEmpty = chosenBuildServer.isEmpty || chosenBuildServer.exists(
        _ == BloopServers.name
      )
      buildChange <- possibleBuildTool match {
        case Some(buildTool) =>
          buildTool.digest(workspace) match {
            case None =>
              scribe.warn(s"Skipping build import, no checksum.")
              Future.successful(BuildChange.None)
            case Some(digest) if isBloopOrEmpty =>
              slowConnectToBloopServer(forceImport, buildTool, digest)
            case Some(digest) =>
              indexer.reloadWorkspaceAndIndex(forceImport, buildTool, digest)
          }
        case None =>
          Future.successful(BuildChange.None)
      }
    } yield buildChange

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
    val connected = if (!buildTools.isAutoConnectable) {
      scribe.warn("Build server is not auto-connectable.")
      Future.successful(BuildChange.None)
    } else {
      autoConnectToBuildServer()
    }

    connected.map { change =>
      buildServerPromise.trySuccess(())
      change
    }
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

  private def autoConnectToBuildServer(): Future[BuildChange] = {
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

    (for {
      _ <- disconnectOldBuildServer()
      maybeSession <- timerProvider.timed("Connected to build server", true) {
        bspConnector.connect(workspace, userConfig)
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
      _ = treeView.init()
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

  private def disconnectOldBuildServer(): Future[Unit] = {
    diagnostics.reset()
    bspSession.foreach(connection =>
      scribe.info(s"Disconnecting from ${connection.main.name} session...")
    )

    bspSession match {
      case None => Future.successful(())
      case Some(session) =>
        bspSession = None
        mainBuildTargetsData.resetConnections(List.empty)
        session.shutdown()
    }
  }

  private def connectToNewBuildServer(
      session: BspSession
  ): Future[BuildChange] = {
    scribe.info(
      s"Connected to Build server: ${session.main.name} v${session.version}"
    )
    cancelables.add(session)
    compilers.cancel()
    bspSession = Some(session)
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
        lastImportedBuilds = bspBuilds.map(_.build)
      }
      _ <- indexer.profiledIndexWorkspace(() => doctor.check())
      _ = if (session.main.isBloop) checkRunningBloopVersion(session.version)
    } yield {
      BuildChange.Reconnected
    }
  }

  private var lastImportedBuilds = List.empty[ImportedBuild]

  val scalaCli: ScalaCli = register(
    new ScalaCli(
      () => compilers,
      compilations,
      () => statusBar,
      buffers,
      () => indexer.profiledIndexWorkspace(() => ()),
      () => diagnostics,
      () => tables,
      () => buildClient,
      languageClient,
      () => clientConfig.initialConfig,
      () => userConfig,
      parseTreesAndPublishDiags,
    )
  )
  buildTargets.addData(scalaCli.buildTargetsData)

  private val indexer = Indexer(
    () => workspaceReload,
    () => doctor,
    languageClient,
    () => bspSession,
    executionContext,
    () => tables,
    () => statusBar,
    timerProvider,
    () => scalafixProvider,
    indexingPromise,
    () =>
      Seq(
        Indexer.BuildTool(
          "main",
          mainBuildTargetsData,
          ImportedBuild.fromList(lastImportedBuilds),
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
    () => focusedDocument,
    focusedDocumentBuildTarget,
    buildTargetClasses,
    () => userConfig,
    sh,
    symbolDocs,
    scalaVersionSelector,
    sourceMapper,
  )

  private def checkRunningBloopVersion(bspServerVersion: String) = {
    if (doctor.isUnsupportedBloopVersion()) {
      val notification = tables.dismissedNotifications.IncompatibleBloop
      if (!notification.isDismissed) {
        val messageParams = IncompatibleBloopVersion.params(
          bspServerVersion,
          BuildInfo.bloopVersion,
          isChangedInSettings = userConfig.bloopVersion != None,
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
          scribe.warn(s"invalid jar: ${e.path}", e.underlying)
        case e: IndexingExceptions.PathIndexingException =>
          scribe.error(s"issues while parsing: ${e.path}", e.underlying)
        case e: IndexingExceptions.InvalidSymbolException =>
          scribe.error(s"searching for `${e.symbol}` failed", e.underlying)
        case _: NoSuchFileException =>
        // only comes for badly configured jar with `/Users` path added.
        case NonFatal(e) =>
          scribe.error("unexpected error during source scanning", e)
      },
      toIndexSource = path => sourceMapper.mappedTo(path).getOrElse(path),
    )
  }

  private def syncUserconfiguration(): Future[Unit] = {
    val supportsConfiguration = for {
      params <- initializeParams
      capabilities <- Option(params.getCapabilities)
      workspace <- Option(capabilities.getWorkspace)
      out <- Option(workspace.getConfiguration())
    } yield out.booleanValue()

    if (supportsConfiguration.getOrElse(false)) {
      val item = new ConfigurationItem()
      item.setSection("metals")
      val params = new ConfigurationParams(List(item).asJava)
      languageClient
        .configuration(params)
        .asScala
        .flatMap { items =>
          items.asScala.headOption match {
            case Some(item) =>
              val json = item.asInstanceOf[JsonElement].getAsJsonObject()
              updateConfiguration(json)
            case None =>
              Future.unit
          }
        }
    } else Future.unit
  }

  private def isMillBuildSc(path: AbsolutePath): Boolean =
    path.toNIO.getFileName.toString == "build.sc" &&
      // for now, this only checks for build.sc, but this could be made more strict in the future
      // (require ./mill or ./.mill-version)
      buildTools.isMill

  /**
   * Returns the absolute path or directory that ScalaCLI imports as ScalaCLI scripts.
   * By default, ScalaCLI tries to import the entire directory as ScalaCLI scripts.
   * However, we have to ensure that there are no clashes with other existing sourceItems
   * see: https://github.com/scalameta/metals/issues/4447
   *
   * @param path the absolute path of the ScalaCLI script to import
   */
  private def scalaCliDirOrFile(path: AbsolutePath): AbsolutePath = {
    val dir = path.parent
    val nioDir = dir.toNIO
    val conflictsWithMainBsp =
      buildTargets.sourceItems.filter(_.exists).exists { item =>
        val nioItem = item.toNIO
        nioDir.startsWith(nioItem) || nioItem.startsWith(nioDir)
      }

    if (conflictsWithMainBsp) path else dir
  }

  def maybeImportScript(path: AbsolutePath): Option[Future[Unit]] = {
    val scalaCliPath = scalaCliDirOrFile(path)
    if (
      ammonite.loaded(path) || scalaCli.loaded(scalaCliPath) || isMillBuildSc(
        path
      )
    )
      None
    else {
      def doImportScalaCli(): Unit =
        scalaCli.start(scalaCliPath).onComplete {
          case Failure(e) =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Scala CLI project $scalaCliPath", e)
          case Success(_) =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedScalaCli
            )
        }
      def doImportAmmonite(): Unit =
        ammonite.start(Some(path)).onComplete {
          case Failure(e) =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportFailed(path.toString)
            )
            scribe.warn(s"Error importing Ammonite script $path", e)
          case Success(_) =>
            languageClient.showMessage(
              Messages.ImportScalaScript.ImportedAmmonite
            )
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
          Future.unit
        } else if (autoImportScalaCli) {
          doImportScalaCli()
          Future.unit
        } else {
          val futureResp = languageClient
            .showMessageRequest(Messages.ImportScalaScript.params())
            .asScala
          futureResp.onComplete {
            case Failure(e) =>
              scribe.warn("Error requesting Scala script import", e)
            case Success(null) =>
              scribe.debug("Scala script import cancelled by user")
            case Success(resp) =>
              resp.getTitle match {
                case Messages.ImportScalaScript.doImportAmmonite =>
                  doImportAmmonite()
                  askAutoImport(
                    tables.dismissedNotifications.AmmoniteImportAuto
                  )
                case Messages.ImportScalaScript.doImportScalaCli =>
                  doImportScalaCli()
                  askAutoImport(
                    tables.dismissedNotifications.ScalaCliImportAuto
                  )
                case _ =>
              }
          }
          futureResp.ignoreValue
        }
      Some(futureRes)
    }
  }

}
