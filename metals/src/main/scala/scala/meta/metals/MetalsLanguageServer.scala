package scala.meta.metals

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspServers
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClasspathSearch
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLspService
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MtagsResolver
import scala.meta.internal.metals.MutableCancelable
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.ThreadPools
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.logging.LanguageClientLogger
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.io.AbsolutePath
import scala.meta.metals.ServerState.ShuttingDown
import scala.meta.metals.lsp.DelegatingScalaService
import scala.meta.metals.lsp.LanguageServer
import scala.meta.metals.lsp.ScalaLspService

import org.eclipse.lsp4j._

// todo https://github.com/scalameta/metals/issues/4789
// extract configuration to separate class

/**
 * Scala Language Server implementation.
 *
 * @param ec
 *   execution context for futures.
 * @param sh
 *   scheduled executor service for scheduling tasks.
 */
class MetalsLanguageServer(
    ec: ExecutionContextExecutorService,
    sh: ScheduledExecutorService,
    buffers: Buffers = Buffers(),
    redirectSystemOut: Boolean = true,
    charset: Charset = StandardCharsets.UTF_8,
    time: Time = Time.system,
    initialServerConfig: MetalsServerConfig = MetalsServerConfig.default,
    initialUserConfig: UserConfiguration = UserConfiguration.default,
    progressTicks: ProgressTicks = ProgressTicks.braille,
    bspGlobalDirectories: List[AbsolutePath] =
      BspServers.globalInstallDirectories,
    isReliableFileWatcher: Boolean = true,
    mtagsResolver: MtagsResolver = MtagsResolver.default(),
    onStartCompilation: () => Unit = () => (),
    classpathSearchIndexer: ClasspathSearch.Indexer =
      ClasspathSearch.Indexer.default,
) extends LanguageServer {

  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.sh", sh)
  ThreadPools.discardRejectedRunnables("MetalsLanguageServer.ec", ec)

  private implicit val executionContext: ExecutionContextExecutorService = ec

  private val serverState =
    new AtomicReference[ServerState](ServerState.Started)

  private val languageClient =
    new AtomicReference[MetalsLanguageClient](NoopLanguageClient)

  private val cancelables = new MutableCancelable()
  private val isCancelled = new AtomicBoolean(false)
  private val isLanguageClientConnected = new AtomicBoolean(false)

  // it's fine to pass null to underlying service, it won't be used before initialize is called
  // and we set it to the correct value in initialize anyway
  private val metalsService = new DelegatingScalaService(null)

  def connectToLanguageClient(languageClient: MetalsLanguageClient): Unit = {
    isLanguageClientConnected.set(true)
    this.languageClient.set(languageClient)
    LanguageClientLogger.languageClient = Some(languageClient)
    cancelables.add(() => languageClient.shutdown())
  }

  /**
   * Cancel all cancelables, but leave thread pools running. This is used only
   * in tests where thread pools are reused.
   */
  def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      cancelables.cancel()
      serverState.get match {
        case ServerState.Initialized(service) => service.cancel()
        case ShuttingDown(service) => service.cancel()
        case _ =>
          scribe.warn(
            s"Server is in state $serverState, cannot invoke cancelAll"
          )
      }
    }
  }

  /**
   * Cancel all cancelables and shutdown thread pools. This is used in
   * production and in tests, after all tests are finished in suite.
   */
  def cancelAll(): Unit = {
    cancel()
    Cancelable.cancelAll(
      List(
        Cancelable(() => ec.shutdown()),
        Cancelable(() => sh.shutdown()),
      )
    )
  }

  override def initialize(
      params: InitializeParams
  ): CompletableFuture[InitializeResult] = {
    if (serverState.get != ServerState.Started) {
      Future
        .failed[InitializeResult](
          new IllegalStateException(
            s"Server state is ${serverState}, expected ${ServerState.Started}"
          )
        )
        .asJava
    } else {
      // NOTE: we purposefully don't check workspaceFolders here
      // since Metals technically doesn't support it. Once we implement
      // https://github.com/scalameta/metals-feature-requests/issues/87 we'll
      // have to change this.
      val root =
        Option(params.getRootUri())
          .orElse(Option(params.getRootPath()))
          .map(_.toAbsolutePath)
      root match {
        // ugly check to avoid starting the server if proper languageClient wasn't plugged
        case _ if !isLanguageClientConnected.get =>
          Future
            .failed(
              new IllegalStateException("Language client wasn't connected!")
            )
            .asJava
        case None =>
          languageClient.get.showMessage(Messages.noRoot)
          Future
            .failed(
              new IllegalArgumentException(
                "There is no root directory in InitializeParams"
              )
            )
            .asJava
        case Some(workspace) =>
          val service = createService(workspace, params)

          setupJna()
          MetalsLogger.setupLspLogger(workspace, redirectSystemOut)

          val clientInfo = Option(params.getClientInfo()).fold("") { info =>
            s"for client ${info.getName()} ${Option(info.getVersion).getOrElse("")}"
          }
          scribe.info(
            s"Started: Metals version ${BuildInfo.metalsVersion} in workspace '$workspace' $clientInfo."
          )

          serverState.set(ServerState.Initialized(service))
          metalsService.underlying = service

          service.initialize()
      }
    }
  }
  private def setupJna(): Unit = {
    // This is required to avoid the following error:
    //   java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.platform.win32.Kernel32
    //     at sbt.internal.io.WinMilli$.getHandle(Milli.scala:277)
    //   There is an incompatible JNA native library installed on this system
    //     Expected: 5.2.2
    //     Found:    3.2.1
    System.setProperty("jna.nosys", "true")
  }

  private def createService(
      workspace: AbsolutePath,
      initializeParams: InitializeParams,
  ): MetalsLspService = new MetalsLspService(
    ec = ec,
    buffers = buffers,
    workspace = workspace,
    client = languageClient.get,
    initializeParams = initializeParams,
    charset = charset,
    time = time,
    initialConfig = initialServerConfig,
    initialUserConfig = initialUserConfig,
    progressTicks = progressTicks,
    bspGlobalDirectories = bspGlobalDirectories,
    sh = sh,
    isReliableFileWatcher = isReliableFileWatcher,
    mtagsResolver = mtagsResolver,
    onStartCompilation = onStartCompilation,
    classpathSearchIndexer = classpathSearchIndexer,
  )

  private val isInitialized = new AtomicBoolean(false)
  override def initialized(
      params: InitializedParams
  ): CompletableFuture[Unit] = {
    // Avoid duplicate `initialized` notifications. During the transition
    // for https://github.com/natebosch/vim-lsc/issues/113 to get fixed,
    // we may have users on a fixed vim-lsc version but with -Dmetals.no-initialized=true
    // enabled.
    if (isInitialized.compareAndSet(false, true)) {
      serverState.get match {
        case ServerState.Initialized(service) =>
          service.initialized()
        case _ =>
          Future.failed(new Exception)
      }
    } else {
      scribe.warn("Ignoring duplicate 'initialized' notification.")
      Future.unit
    }
  }.recover { case NonFatal(e) =>
    scribe.error("Unexpected error initializing server: ", e)
  }.asJava

  override def shutdown(): CompletableFuture[Unit] = serverState.get match {
    case ServerState.Initialized(server) =>
      scribe.info("Shutting down server")
      server
        .shutdown()
        .thenApply(_ => serverState.set(ServerState.ShuttingDown(server)))
    case _ =>
      scribe.warn(s"Ignoring shutdown request, server is $serverState state")
      Future.unit.asJava
  }

  override def exit(): Unit = serverState.get match {
    case ServerState.ShuttingDown(server) =>
      scribe.info("Exiting server")
      server.exit()
    case _ =>
      scribe.warn(s"Ignoring exit request, server is $serverState state")
      ()
  }

  override val getScalaService: ScalaLspService = metalsService

  // todo https://github.com/scalameta/metals/issues/4785
  @deprecated
  def getOldMetalsLanguageServer: MetalsLspService = serverState.get match {
    case ServerState.Initialized(service) => service
    case _ => throw new IllegalStateException("Server is not initialized")
  }

}
