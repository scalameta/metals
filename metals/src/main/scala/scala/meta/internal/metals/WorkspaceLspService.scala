package scala.meta.internal.metals

import java.net.URI
import java.nio.file.ProviderMismatchException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLspService
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.config.StatusBarState
import scala.meta.internal.metals.debug.DebugProvider
import scala.meta.internal.metals.debug.DiscoveryFailures
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.metals.logging.LanguageClientLogger
import scala.meta.internal.parsing.ClassFinderGranularity
import scala.meta.internal.pc
import scala.meta.internal.tvp.MetalsTreeViewChildrenResult
import scala.meta.internal.tvp.MetalsTreeViewProvider
import scala.meta.internal.tvp.NoopTreeViewProvider
import scala.meta.internal.tvp.TreeViewChildrenParams
import scala.meta.internal.tvp.TreeViewNodeCollapseDidChangeParams
import scala.meta.internal.tvp.TreeViewNodeRevealResult
import scala.meta.internal.tvp.TreeViewParentParams
import scala.meta.internal.tvp.TreeViewParentResult
import scala.meta.internal.tvp.TreeViewProvider
import scala.meta.internal.tvp.TreeViewVisibilityDidChangeParams
import scala.meta.io.AbsolutePath
import scala.meta.metals.lsp.ScalaLspService

import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import io.undertow.server.HttpServerExchange
import org.eclipse.lsp4j
import org.eclipse.lsp4j.CallHierarchyIncomingCall
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCall
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeLens
import org.eclipse.lsp4j.CodeLensParams
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SelectionRange
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages

class WorkspaceLspService(
    ec: ExecutionContextExecutorService,
    sh: ju.concurrent.ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    client: MetalsLanguageClient,
    initializeParams: lsp4j.InitializeParams,
    val folders: List[Folder],
    fallbackServicePath: => AbsolutePath,
) extends ScalaLspService {
  import serverInputs._
  implicit val ex: ExecutionContextExecutorService = ec
  implicit val rc: ReportContext = LoggerReportContext
  private val cancelables = new MutableCancelable()
  val fallbackIsInitialized: ju.concurrent.atomic.AtomicBoolean =
    new ju.concurrent.atomic.AtomicBoolean(false)
  var httpServer: Option[MetalsHttpServer] = None

  private val clientConfig =
    ClientConfiguration(
      serverInputs.initialServerConfig,
      initializeParams,
    )

  private val languageClient = {
    val languageClient =
      new ConfiguredLanguageClient(client, clientConfig, this)
    // Set the language client so that we can forward log messages to the client
    LanguageClientLogger.languageClient = Some(languageClient)
    cancelables.add(() => languageClient.shutdown())
    languageClient
  }

  private val workDoneProgress = register {
    new WorkDoneProgress(languageClient, time)
  }

  private val userConfigSync =
    new UserConfigurationSync(initializeParams, languageClient, clientConfig)

  val statusBar: StatusBar = register {
    new StatusBar(
      languageClient,
      time,
    )
  }

  private val shellRunner = register {
    new ShellRunner(time, workDoneProgress)
  }

  private val focusedDocument: AtomicReference[Option[AbsolutePath]] =
    new AtomicReference(None)
  private val recentlyFocusedFiles = new ActiveFiles(time)

  private val timerProvider: TimerProvider = new TimerProvider(time)

  val doctor: HeadDoctor =
    new HeadDoctor(
      () => folderServices.map(_.doctor) ++ optFallback.map(_.doctor),
      () => httpServer,
      clientConfig,
      languageClient,
      clientConfig.isHttpEnabled(),
    )

  private val bspStatus = new BspStatus(
    languageClient,
    isBspStatusProvider = clientConfig.bspStatusBarState() == StatusBarState.On,
  )

  def setFocusedDocument(newFocusedDocument: Option[AbsolutePath]): Unit = {
    focusedDocument.get().foreach(focused => recentlyFocusedFiles.add(focused))
    focusedDocument.set(newFocusedDocument)
    newFocusedDocument
      .flatMap(getServiceForOpt)
      .foreach(service => bspStatus.focus(service.path))
  }

  lazy val fallbackService: FallbackMetalsLspService = {
    fallbackIsInitialized.set(true)
    new FallbackMetalsLspService(
      ec,
      sh,
      serverInputs,
      languageClient,
      initializeParams,
      clientConfig,
      statusBar,
      () => focusedDocument.get(),
      shellRunner,
      timerProvider,
      fallbackServicePath,
      Some("fallback-service"),
      doctor,
      workDoneProgress,
      bspStatus,
    )
  }

  def createService(folder: Folder): ProjectMetalsLspService =
    folder match {
      case Folder(uri, name) =>
        new ProjectMetalsLspService(
          ec,
          sh,
          serverInputs,
          languageClient,
          initializeParams,
          clientConfig,
          statusBar,
          () => focusedDocument.get(),
          shellRunner,
          timerProvider,
          initTreeView,
          uri,
          name,
          doctor,
          bspStatus,
          workDoneProgress,
          maxScalaCliServers = 3,
        )
    }

  val workspaceFolders: WorkspaceFolders =
    new WorkspaceFolders(
      folders,
      createService,
      redirectSystemOut,
      serverInputs.initialServerConfig,
      userConfigSync,
    )

  def folderServices = workspaceFolders.getFolderServices
  def nonScalaProjects = workspaceFolders.nonScalaProjects
  def optFallback: Option[FallbackMetalsLspService] =
    Option.when(fallbackIsInitialized.get())(fallbackService)

  val treeView: TreeViewProvider =
    if (clientConfig.isTreeViewProvider()) {
      new MetalsTreeViewProvider(
        () => folderServices.map(_.treeView),
        languageClient,
      )
    } else NoopTreeViewProvider

  def initTreeView(): Unit = treeView.init()

  def currentOrHeadOrFallback: MetalsLspService =
    currentFolder
      .orElse { folderServices.headOption }
      .getOrElse(fallbackService)

  private val newProjectProvider: NewProjectProvider = new NewProjectProvider(
    languageClient,
    workDoneProgress,
    clientConfig,
    shellRunner,
    clientConfig.icons(),
    () => currentOrHeadOrFallback.path,
  )

  private val githubNewIssueUrlCreator = new GithubNewIssueUrlCreator(
    () => folderServices.map(_.gitHubIssueFolderInfo),
    initializeParams.getClientInfo(),
    charset,
  )

  private val workspaceChoicePopup: WorkspaceChoicePopup =
    new WorkspaceChoicePopup(
      () => folderServices,
      languageClient,
    )

  def register[T <: Cancelable](cancelable: T): T = {
    cancelables.add(cancelable)
    cancelable
  }

  def getFolderForOpt[T <: Folder](
      path: AbsolutePath,
      folders: List[T],
  ): Option[T] =
    try {
      for {
        workSpaceFolder <- folders
          .filter(service => path.toNIO.startsWith(service.path.toNIO))
          .sortBy(_.path.toNIO.getNameCount())
          .lastOption
      } yield workSpaceFolder
    } catch {
      case _: ProviderMismatchException => None
    }

  def getServiceForOpt(path: AbsolutePath): Option[ProjectMetalsLspService] =
    getFolderForOpt(path, folderServices).orElse {
      folderServices.find { service =>
        if (path.isJarFileSystem) {
          service.buildTargets.inferBuildTarget(path).nonEmpty
        } else {
          service.buildTargets.all
            .exists(bt => bt.baseDirectoryPath.exists(path.startWith))
        }
      }
    }

  def getServiceFor(path: AbsolutePath): MetalsLspService =
    getServiceForOpt(path).getOrElse(fallbackService)

  def getServiceForOpt(uri: String): Option[ProjectMetalsLspService] = {
    // "metalsDecode" prefix is used for showing special files and is not an actual file system
    val strippedUri = uri.stripPrefix("metalsDecode:")
    for {
      path <- strippedUri.toAbsolutePathSafe()
      service <-
        if (strippedUri.startsWith("jar:"))
          folderServices.find(_.buildTargets.inverseSources(path).nonEmpty)
        else getServiceForOpt(path)
    } yield service
  }

  def getServiceFor(uri: String): MetalsLspService =
    getServiceForOpt(uri).getOrElse(fallbackService)

  def currentFolder: Option[MetalsLspService] =
    focusedDocument.get().flatMap(getServiceForOpt)

  /**
   * Execute on current folder (for focused document).
   * In no focused document create a popup for user to choose the folder.
   * @param f -- action to be executed
   * @param actionName -- action name to display for popup
   */
  def onCurrentFolder[A](
      f: ProjectMetalsLspService => Future[A],
      actionName: String,
      default: () => A,
  ): Future[A] = {
    def currentService(): Future[Option[ProjectMetalsLspService]] =
      folderServices match {
        case Nil => Future { None }
        case head :: Nil => Future { Some(head) }
        case _ =>
          focusedDocument.get().flatMap(getServiceForOpt) match {
            case Some(service) => Future { Some(service) }
            case None =>
              workspaceChoicePopup.interactiveChooseFolder(actionName)
          }
      }
    currentService().flatMap {
      case Some(service) => f(service)
      case None =>
        languageClient
          .showMessage(
            lsp4j.MessageType.Info,
            s"No workspace folder chosen to execute $actionName.",
          )
        Future.successful(default())
    }
  }

  def onCurrentFolder(
      f: ProjectMetalsLspService => Future[Unit],
      actionName: String,
  ): Future[Unit] =
    onCurrentFolder(f, actionName, () => ())

  def foreachSeq[A](
      f: ProjectMetalsLspService => Future[A],
      ignoreValue: Boolean = false,
  ): CompletableFuture[Object] = {
    val res = Future.sequence(folderServices.map(f))
    if (ignoreValue) res.ignoreValue.asJavaObject
    else res.asJavaObject
  }

  def foreachSeqIncludeFallback[A](
      f: MetalsLspService => Future[A],
      ignoreValue: Boolean = false,
  ): CompletableFuture[Object] = {
    val services = folderServices ++ optFallback
    val res = Future.sequence(services.map(f))
    if (ignoreValue) res.ignoreValue.asJavaObject
    else res.asJavaObject
  }

  def collectSeq[A, B](f: MetalsLspService => Future[A])(
      compose: List[A] => B
  ): Future[B] =
    Future.sequence(folderServices.map(f)).collect { case v => compose(v) }

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] = {
    focusedDocument.get().foreach(recentlyFocusedFiles.add)
    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    setFocusedDocument(Some(path))
    val service = getServiceForOpt(path)
      .orElse {
        if (path.filename.isScalaOrJavaFilename) {
          getFolderForOpt(path, nonScalaProjects)
            .flatMap(workspaceFolders.convertToScalaProject)
        } else None
      }
      .getOrElse(fallbackService)
    service.didOpen(params)
  }

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] = {
    val uri = params.getTextDocument().getUri()
    /* If a file changed that was most likely caused by the user,
     * we should consider it as the focused document.
     *
     * This isn't needed if a client send those updates itself.
     */
    if (!clientConfig.isDidFocusProvider())
      setFocusedDocument(Some(uri.toAbsolutePath))
    getServiceFor(uri).didChange(params)
  }

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    if (focusedDocument.get().contains(path)) {
      setFocusedDocument(recentlyFocusedFiles.pollRecent())
    }
    getServiceFor(params.getTextDocument().getUri()).didClose(params)
  }

  override def didSave(
      params: DidSaveTextDocumentParams
  ): CompletableFuture[Unit] =
    getServiceFor(params.getTextDocument().getUri()).didSave(params)

  override def definition(
      position: TextDocumentPositionParams
  ): CompletableFuture[ju.List[Location]] =
    getServiceFor(position.getTextDocument().getUri()).definition(position)

  override def typeDefinition(
      position: TextDocumentPositionParams
  ): CompletableFuture[ju.List[Location]] =
    getServiceFor(position.getTextDocument().getUri()).typeDefinition(position)

  override def implementation(
      position: TextDocumentPositionParams
  ): CompletableFuture[ju.List[Location]] =
    getServiceFor(position.getTextDocument().getUri()).implementation(position)

  override def hover(params: HoverExtParams): CompletableFuture[Hover] =
    getServiceFor(params.textDocument.getUri()).hover(params)

  override def inlayHints(
      params: lsp4j.InlayHintParams
  ): CompletableFuture[java.util.List[lsp4j.InlayHint]] =
    getServiceFor(params.getTextDocument.getUri()).inlayHints(params)

  override def inlayHintResolve(
      inlayHint: lsp4j.InlayHint
  ): CompletableFuture[lsp4j.InlayHint] =
    currentFolder
      .map(_.inlayHintResolve(inlayHint))
      .getOrElse(Future.successful(inlayHint).asJava)

  override def documentHighlights(
      params: TextDocumentPositionParams
  ): CompletableFuture[ju.List[DocumentHighlight]] =
    getServiceFor(params.getTextDocument.getUri()).documentHighlights(params)

  override def documentSymbol(params: DocumentSymbolParams): CompletableFuture[
    messages.Either[ju.List[DocumentSymbol], ju.List[SymbolInformation]]
  ] =
    getServiceFor(params.getTextDocument.getUri()).documentSymbol(params)

  override def formatting(
      params: DocumentFormattingParams
  ): CompletableFuture[ju.List[TextEdit]] =
    getServiceFor(params.getTextDocument.getUri()).formatting(params)

  override def onTypeFormatting(
      params: DocumentOnTypeFormattingParams
  ): CompletableFuture[ju.List[TextEdit]] =
    getServiceFor(params.getTextDocument.getUri()).onTypeFormatting(params)

  override def rangeFormatting(
      params: DocumentRangeFormattingParams
  ): CompletableFuture[ju.List[TextEdit]] =
    getServiceFor(params.getTextDocument.getUri()).rangeFormatting(params)

  override def prepareRename(
      params: TextDocumentPositionParams
  ): CompletableFuture[lsp4j.Range] =
    getServiceFor(params.getTextDocument.getUri()).prepareRename(params)

  override def rename(params: RenameParams): CompletableFuture[WorkspaceEdit] =
    getServiceFor(params.getTextDocument.getUri()).rename(params)

  override def references(
      params: ReferenceParams
  ): CompletableFuture[ju.List[Location]] =
    getServiceFor(params.getTextDocument.getUri()).references(params)

  override def prepareCallHierarchy(
      params: CallHierarchyPrepareParams
  ): CompletableFuture[ju.List[CallHierarchyItem]] =
    getServiceFor(params.getTextDocument.getUri()).prepareCallHierarchy(params)

  override def callHierarchyIncomingCalls(
      params: CallHierarchyIncomingCallsParams
  ): CompletableFuture[ju.List[CallHierarchyIncomingCall]] =
    getServiceFor(params.getItem.getUri).callHierarchyIncomingCalls(params)

  override def callHierarchyOutgoingCalls(
      params: CallHierarchyOutgoingCallsParams
  ): CompletableFuture[ju.List[CallHierarchyOutgoingCall]] =
    getServiceFor(params.getItem.getUri).callHierarchyOutgoingCalls(params)

  override def completion(
      params: CompletionParams
  ): CompletableFuture[CompletionList] =
    getServiceFor(params.getTextDocument.getUri).completion(params)

  override def completionItemResolve(
      item: CompletionItem
  ): CompletableFuture[CompletionItem] =
    currentFolder
      .map(_.completionItemResolve(item))
      .getOrElse(Future.successful(item).asJava)

  override def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    getServiceFor(params.getTextDocument.getUri).signatureHelp(params)

  override def codeAction(
      params: CodeActionParams
  ): CompletableFuture[ju.List[CodeAction]] =
    getServiceFor(params.getTextDocument.getUri).codeAction(params)

  override def codeActionResolve(
      codeAction: CodeAction
  ): CompletableFuture[CodeAction] =
    currentFolder
      .map(
        _.codeActionResolve(codeAction)
      )
      .getOrElse(Future.successful(codeAction).asJava)

  override def codeLens(
      params: CodeLensParams
  ): CompletableFuture[ju.List[CodeLens]] =
    getServiceFor(params.getTextDocument.getUri).codeLens(params)

  override def foldingRange(
      params: FoldingRangeRequestParams
  ): CompletableFuture[ju.List[FoldingRange]] =
    getServiceFor(params.getTextDocument.getUri).foldingRange(params)

  override def selectionRange(
      params: SelectionRangeParams
  ): CompletableFuture[ju.List[SelectionRange]] =
    getServiceFor(params.getTextDocument.getUri).selectionRange(params)

  override def semanticTokensFull(
      params: SemanticTokensParams
  ): CompletableFuture[SemanticTokens] =
    getServiceFor(params.getTextDocument.getUri).semanticTokensFull(params)

  override def workspaceSymbol(
      params: WorkspaceSymbolParams
  ): CompletableFuture[ju.List[lsp4j.SymbolInformation]] =
    CancelTokens.future { token =>
      collectSeq(_.workspaceSymbol(params, token))(_.flatten.asJava)
    }

  override def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit] =
    CancelTokens.future { _ =>
      val moves = params.getFiles.asScala.toSeq.map { rename =>
        val service = getServiceFor(rename.getOldUri())
        val oldPath = rename.getOldUri().toAbsolutePath
        val newPath = rename.getNewUri().toAbsolutePath
        /* Changing package for files moved out of the workspace or between folders will cause unexpected issues
         * This showed up with emacs automated backups.
         */
        if (newPath.startWith(service.path))
          service.willRenameFile(oldPath, newPath)
        else
          Future.successful(
            new WorkspaceEdit(Map.empty[String, ju.List[TextEdit]].asJava)
          )
      }
      Future.sequence(moves).map(_.mergeChanges)
    }

  override def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] =
    userConfigSync.onDidChangeConfiguration(params, folderServices).asJava

  override def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] =
    Future
      .sequence(
        params
          .getChanges()
          .asScala
          .iterator
          .toList
          .map(event => {
            (event, getServiceFor(event.getUri()))
          })
          .groupBy(_._2)
          .map { case (service, paths) =>
            service.didChangeWatchedFiles(paths.map(_._1))
          }
      )
      .ignoreValue
      .asJava

  override def didChangeWorkspaceFolders(
      params: lsp4j.DidChangeWorkspaceFoldersParams
  ): CompletableFuture[Unit] = {
    val removed =
      params
        .getEvent()
        .getRemoved()
        .map(Folder(_, isKnownMetalsProject = false))
        .asScala
        .toList
    val added =
      params
        .getEvent()
        .getAdded()
        .map(Folder(_, isKnownMetalsProject = false))
        .asScala
        .toList

    workspaceFolders
      .changeFolderServices(removed, added)
      .asJava
  }

  override def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] = {
    Future {
      treeView.children(params)
    }.asJava
  }

  override def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] = {
    Future {
      treeView.parent(params)
    }.asJava
  }

  override def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      treeView.onVisibilityDidChange(params)
    }.asJava

  override def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      treeView.onCollapseDidChange(params)
    }.asJava

  override def treeViewReveal(
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

  override def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[ju.List[Location]] =
    collectSeq(_.findTextInDependencyJars(params))(_.flatten.asJava).asJava

  override def didCancelWorkDoneProgress(
      params: lsp4j.WorkDoneProgressCancelParams
  ): Unit = workDoneProgress.canceled(params.getToken())

  def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    Future {
      doctor.onVisibilityDidChange(params.visible)
    }.asJava

  override def didFocus(
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
      case Some(uri) =>
        setFocusedDocument(Some(uri.toAbsolutePath))
        getServiceFor(uri).didFocus(uri)
      case None =>
        CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    }
  }

  override def sync(
      params: AnyRef
  ): CompletableFuture[Unit] = {
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
      case Some(uri) =>
        if (uri.startsWith("file://")) {
          getServiceFor(uri).sync(uri).asJava
        } else {
          scribe.warn(
            s"Can only sync file:// URIs, got $uri"
          )
          CompletableFuture.completedFuture(())
        }
      case None =>
        CompletableFuture.completedFuture(())
    }
  }

  private def failedRequest(
      message: String
  ): Future[Object] = {
    Future
      .failed(
        new ResponseErrorException(
          new messages.ResponseError(
            messages.ResponseErrorCode.InvalidParams,
            message,
            null,
          )
        )
      )
  }

  private def onFirstSatifying[T, R](
      mapTo: MetalsLspService => Future[T]
  )(
      satisfies: T => Boolean,
      exec: (MetalsLspService, T) => Future[R],
      onNotFound: () => Future[R],
  ): Future[R] =
    Future
      .sequence(folderServices.map(service => mapTo(service).map((service, _))))
      .flatMap { services =>
        services.find { case (_, value) => satisfies(value) } match {
          case Some(found) => Future.successful(Some(found))
          case None if fallbackIsInitialized.get() =>
            mapTo(fallbackService).map { value =>
              if (satisfies(value)) Some((fallbackService, value))
              else None
            }
          case None => Future.successful(None)
        }
      }
      .flatMap(_.map(exec.tupled).getOrElse(onNotFound()))

  override def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] =
    params match {
      case ServerCommands.ScanWorkspaceSources() =>
        foreachSeqIncludeFallback(_.indexSources(), ignoreValue = true)
      case ServerCommands.RestartBuildServer() =>
        onCurrentFolder(
          _.connect(CreateSession(shutdownBuildServer = true)).ignoreValue,
          ServerCommands.RestartBuildServer.title,
        ).asJavaObject
      case ServerCommands.GenerateBspConfig() =>
        onCurrentFolder(
          _.generateBspConfig(),
          ServerCommands.GenerateBspConfig.title,
        ).asJavaObject
      case ServerCommands.ImportBuild() =>
        onCurrentFolder(
          _.connectionProvider.slowConnectToBuildServer(forceImport = true),
          ServerCommands.ImportBuild.title,
          default = () => BuildChange.None,
        ).asJavaObject
      case ServerCommands.ConnectBuildServer() =>
        onCurrentFolder(
          _.connectionProvider.quickConnectToBuildServer(),
          ServerCommands.ConnectBuildServer.title,
          default = () => BuildChange.None,
        ).asJavaObject
      case ServerCommands.DisconnectBuildServer() =>
        onCurrentFolder(
          _.connect(Disconnect(shutdownBuildServer = false)).ignoreValue,
          ServerCommands.DisconnectBuildServer.title,
        ).asJavaObject
      case ServerCommands.DecodeFile(uri) =>
        getServiceFor(uri).decodeFile(uri).asJavaObject
      case ServerCommands.DiscoverTestSuites(params) =>
        Option(params.uri) match {
          case None =>
            Future
              .sequence(folderServices.map(_.discoverTestSuites(uri = None)))
              .map(_.flatten.asJava)
              .asJavaObject
          case Some(uri) =>
            getServiceFor(params.uri)
              .discoverTestSuites(uri = Some(uri))
              .map(_.asJava)
              .asJavaObject
        }
      case ServerCommands.DiscoverMainClasses(unresolvedParams) =>
        val discovered = Option(unresolvedParams.path) match {
          case None =>
            Future
              .find {
                folderServices
                  .map {
                    _.discoverMainClasses(unresolvedParams)
                  }
              }(_ => true)
              .flatMap { mains =>
                mains.headOption.fold(
                  Future.failed[DebugSessionParams](
                    DiscoveryFailures
                      .NoMainClassFoundException(unresolvedParams.mainClass)
                  )
                )(Future.successful(_))
              }

          case Some(path) =>
            getServiceFor(path)
              .discoverMainClasses(unresolvedParams)
        }
        discovered.liftToLspError.asJavaObject
      case ServerCommands.ResetWorkspace() =>
        maybeResetWorkspace().asJavaObject
      case ServerCommands.RunScalafix(params) =>
        val uri = params.getTextDocument().getUri()
        getServiceFor(uri).runScalafix(uri).asJavaObject
      case ServerCommands.ScalafixRunOnly(params) =>
        val uri = params.textDocumentPositionParams.getTextDocument().getUri()
        val rules =
          Option(params.rules).fold(List.empty[String])(_.asScala.toList)
        getServiceFor(uri).runScalafixRules(uri, rules).asJavaObject
      case ServerCommands.ChooseClass(params) =>
        val uri = params.textDocument.getUri()
        val searchGranularity =
          if (params.kind == "class") ClassFinderGranularity.ClassFiles
          else ClassFinderGranularity.Tasty
        getServiceFor(uri).chooseClass(uri, searchGranularity).asJavaObject
      case ServerCommands.RunDoctor() =>
        Future {
          doctor.executeRunDoctor()
        }.asJavaObject
      case ServerCommands.ZipReports() =>
        Future {
          val zip =
            ZipReportsProvider.zip(folderServices.map(_.folderReportsZippper))
          val pos = new lsp4j.Position(0, 0)
          val location = new Location(
            zip.toURI.toString(),
            new lsp4j.Range(pos, pos),
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
      case ServerCommands.ListBuildTargets() =>
        Future {
          folderServices
            .flatMap(
              _.buildTargets.all.toList
                .map(_.getDisplayName())
                .sorted
            )
            .asJava
        }.asJavaObject
      case ServerCommands.BspSwitch() =>
        onCurrentFolder(
          _.switchBspServer(),
          ServerCommands.BspSwitch.title,
        ).asJavaObject
      case ServerCommands.OpenIssue() =>
        Future
          .successful(Urls.openBrowser(githubNewIssueUrlCreator.buildUrl()))
          .asJavaObject
      case OpenBrowserCommand(url) =>
        Future.successful(Urls.openBrowser(url)).asJavaObject
      case ServerCommands.CascadeCompile() =>
        onCurrentFolder(
          _.cascadeCompile(),
          ServerCommands.CascadeCompile.title,
        ).asJavaObject
      case ServerCommands.CleanCompile() =>
        onCurrentFolder(
          _.cleanCompile(),
          ServerCommands.CleanCompile.title,
        ).asJavaObject
      case ServerCommands.CancelCompile() =>
        foreachSeqIncludeFallback(_.cancelCompile(), ignoreValue = true)
      case ServerCommands.PresentationCompilerRestart() =>
        foreachSeqIncludeFallback(_.restartCompiler(), ignoreValue = true)
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
            location <- folderServices.foldLeft(Option.empty[Location]) {
              case (None, service) => service.getLocationForSymbol(symbol)
              case (someLoc, _) => someLoc
            }
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
        onCurrentFolder(
          service =>
            Future {
              val log = service.path.resolve(Directories.log)
              val linesCount = log.readText.linesIterator.size
              val pos = new lsp4j.Position(linesCount, 0)
              val location = new Location(
                log.toURI.toString(),
                new lsp4j.Range(pos, pos),
              )
              languageClient.metalsExecuteClientCommand(
                ClientCommands.GotoLocation.toExecuteCommandParams(
                  ClientCommands.WindowLocation(
                    location.getUri(),
                    location.getRange(),
                  )
                )
              )
            },
          ServerCommands.GotoLog.title,
        ).asJavaObject

      case ServerCommands.StartDebugAdapter(params) if params.getData != null =>
        val targets = params.getTargets().asScala
        folderServices
          .find(s => targets.forall(s.supportsBuildTarget(_).isDefined))
          .orElse {
            Option.when(
              fallbackIsInitialized.get() && targets
                .forall(fallbackService.supportsBuildTarget(_).isDefined)
            )(fallbackService)
          } match {
          case Some(service) =>
            service.startDebugProvider(params).liftToLspError.asJavaObject
          case None =>
            failedRequest(
              s"Could not find folder for build targets: ${targets.mkString(",")}"
            ).asJavaObject
        }
      case ServerCommands.StartMainClass(params) if params.mainClass != null =>
        DebugProvider
          .getResultFromSearches(
            folderServices.map(_.mainClassSearch(params))
          )
          .liftToLspError
          .asJavaObject

      case ServerCommands.StartTestSuite(params)
          if params.target != null && params.requestData != null =>
        onFirstSatifying(service =>
          Future.successful(service.supportsBuildTarget(params.target))
        )(
          _.isDefined,
          (service, someTarget) =>
            service.startTestSuite(someTarget.get, params),
          () => failedRequest(s"Could not find '${params.target}' build target"),
        ).asJavaObject
      case ServerCommands.ResolveAndStartTestSuite(params)
          if params.testClass != null =>
        DebugProvider
          .getResultFromSearches(
            folderServices.map(_.testClassSearch(params))
          )
          .liftToLspError
          .asJavaObject
      case ServerCommands.StartAttach(params) if params.hostName != null =>
        onFirstSatifying(service =>
          Future.successful(
            service.findBuildTargetByDisplayName(params.buildTarget)
          )
        )(
          _.isDefined,
          (service, someTarget) =>
            service.createDebugSession(someTarget.get.getId()),
          () =>
            failedRequest(
              s"Could not find '${params.buildTarget}' build target"
            ),
        ).asJavaObject
      case ServerCommands.DiscoverAndRun(params) =>
        getServiceFor(params.path)
          .debugDiscovery(params)
          .liftToLspError
          .asJavaObject
      case ServerCommands.AnalyzeStacktrace(content) =>
        Future {
          // Getting the service for focused document and first one otherwise
          val service =
            focusedDocument.get().map(getServiceFor).getOrElse(fallbackService)
          val command = service.analyzeStackTrace(content)
          command.foreach(languageClient.metalsExecuteClientCommand)
          scribe.debug(s"Executing AnalyzeStacktrace ${command}")
        }.asJavaObject

      case ServerCommands.GotoSuperMethod(textDocumentPositionParams) =>
        getServiceFor(textDocumentPositionParams.getTextDocument().getUri())
          .gotoSupermethod(textDocumentPositionParams)

      case ServerCommands.SuperMethodHierarchy(textDocumentPositionParams) =>
        scribe.debug(s"Executing SuperMethodHierarchy ${params.getCommand()}")
        getServiceFor(textDocumentPositionParams.getTextDocument().getUri())
          .superMethodHierarchy(textDocumentPositionParams)

      case ServerCommands.ResetChoicePopup() =>
        val argsMaybe = Option(params.getArguments())
        (argsMaybe.flatMap(_.asScala.headOption) match {
          case Some(arg: JsonPrimitive) =>
            val value = arg.getAsString().replace("+", " ")
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} for choice ${value}"
            )
            onCurrentFolder(_.resetPopupChoice(value), "reset choice")
          case _ =>
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} in interactive mode."
            )
            onCurrentFolder(
              _.interactivePopupChoiceReset(),
              ServerCommands.ResetChoicePopup.title,
            )
        }).asJavaObject

      case ServerCommands.ResetNotifications() =>
        onCurrentFolder(
          _.resetNotifications(),
          ServerCommands.ResetNotifications.title,
        ).asJavaObject

      case ServerCommands.NewScalaFile(args) =>
        val directoryURI = args
          .lift(0)
          .flatten
          .orElse(focusedDocument.get().map(_.parent.toURI.toString()))
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        directoryURI
          .map(getServiceFor)
          .getOrElse(fallbackService)
          .createFile(directoryURI, name, fileType, isScala = true)
      case ServerCommands.NewJavaFile(args) =>
        val directoryURI = args
          .lift(0)
          .flatten
          .orElse(focusedDocument.get().map(_.parent.toURI.toString()))
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        directoryURI
          .map(getServiceFor)
          .getOrElse(fallbackService)
          .createFile(directoryURI, name, fileType, isScala = false)
      case ServerCommands.StartAmmoniteBuildServer() =>
        val res = for {
          path <- focusedDocument.get()
          service <- getServiceForOpt(path)
        } yield service.ammoniteStart()
        res.getOrElse(Future.unit).asJavaObject
      case ServerCommands.StopAmmoniteBuildServer() =>
        foreachSeq(_.ammoniteStop(), ignoreValue = false)
      case ServerCommands.StartScalaCliServer() =>
        val res = focusedDocument.get() match {
          case None => Future.unit
          case Some(path) =>
            getServiceFor(path).startScalaCli(path)
        }
        res.asJavaObject
      case ServerCommands.StopScalaCliServer() =>
        foreachSeqIncludeFallback(_.stopScalaCli(), ignoreValue = true)
      case ServerCommands.NewScalaProject() =>
        newProjectProvider
          .createNewProjectFromTemplate(currentOrHeadOrFallback.javaHome)
          .asJavaObject
      case ServerCommands.CopyWorksheetOutput(path) =>
        getServiceFor(path).copyWorksheetOutput(path.toAbsolutePath)
      case actionCommand
          if currentOrHeadOrFallback.allActionCommandsIds(
            actionCommand.getCommand()
          ) =>
        CancelTokens.future { token =>
          currentFolder
            .map(
              _.executeCodeActionCommand(params, token)
                .recover(
                  getOptDisplayableMessage andThen (languageClient
                    .showMessage(lsp4j.MessageType.Info, _))
                )
            )
            .getOrElse(Future.successful(()))
            .withObjectValue
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

  def initialize(): CompletableFuture[lsp4j.InitializeResult] = {
    timerProvider
      .timed("initialize")(Future {
        val capabilities = new lsp4j.ServerCapabilities()
        capabilities.setExecuteCommandProvider(
          new lsp4j.ExecuteCommandOptions(
            (ServerCommands.allIds ++ currentOrHeadOrFallback.allActionCommandsIds).toList.asJava
          )
        )
        capabilities.setFoldingRangeProvider(true)
        capabilities.setSelectionRangeProvider(true)
        val semanticTokenOptions =
          new lsp4j.SemanticTokensWithRegistrationOptions()
        semanticTokenOptions.setFull(true)
        semanticTokenOptions.setRange(false)
        semanticTokenOptions.setLegend(
          new lsp4j.SemanticTokensLegend(
            pc.SemanticTokens.TokenTypes.asJava,
            pc.SemanticTokens.TokenModifiers.asJava,
          )
        )
        capabilities.setSemanticTokensProvider(semanticTokenOptions)
        capabilities.setCodeLensProvider(new lsp4j.CodeLensOptions(false))
        capabilities.setDefinitionProvider(true)
        capabilities.setTypeDefinitionProvider(true)
        capabilities.setImplementationProvider(true)
        capabilities.setHoverProvider(true)
        capabilities.setReferencesProvider(true)
        val renameOptions = new lsp4j.RenameOptions()
        renameOptions.setPrepareProvider(true)
        capabilities.setRenameProvider(renameOptions)
        capabilities.setDocumentHighlightProvider(true)
        capabilities.setDocumentOnTypeFormattingProvider(
          new lsp4j.DocumentOnTypeFormattingOptions("\n", List("\"").asJava)
        )
        capabilities.setDocumentRangeFormattingProvider(
          initialServerConfig.allowMultilineStringFormatting
        )
        capabilities.setSignatureHelpProvider(
          new lsp4j.SignatureHelpOptions(List("(", "[", ",").asJava)
        )
        capabilities.setCompletionProvider(
          new lsp4j.CompletionOptions(
            clientConfig.isCompletionItemResolve(),
            List(".", "*", "$").asJava,
          )
        )
        capabilities.setCallHierarchyProvider(true)
        capabilities.setWorkspaceSymbolProvider(true)
        capabilities.setDocumentSymbolProvider(true)
        capabilities.setDocumentFormattingProvider(true)
        val codeActionOptions = new lsp4j.CodeActionOptions()

        if (initializeParams.supportsCodeActionLiterals) {
          codeActionOptions.setCodeActionKinds(
            List(
              lsp4j.CodeActionKind.QuickFix,
              lsp4j.CodeActionKind.Refactor,
              lsp4j.CodeActionKind.SourceOrganizeImports,
            ).asJava
          )
        }
        codeActionOptions.setResolveProvider(true)

        capabilities.setCodeActionProvider(codeActionOptions)
        val inlayHintsCapabilities = new lsp4j.InlayHintRegistrationOptions()
        inlayHintsCapabilities.setResolveProvider(true)
        capabilities.setInlayHintProvider(inlayHintsCapabilities)

        val textDocumentSyncOptions = new lsp4j.TextDocumentSyncOptions
        textDocumentSyncOptions.setChange(lsp4j.TextDocumentSyncKind.Full)
        // We don't use the text at all.
        textDocumentSyncOptions.setSave(
          new lsp4j.SaveOptions( /* includeText = */ false)
        )
        textDocumentSyncOptions.setOpenClose(true)

        val scalaFilesPattern = new lsp4j.FileOperationPattern("**/*.scala")
        scalaFilesPattern.setMatches(lsp4j.FileOperationPatternKind.File)
        val folderFilesPattern = new lsp4j.FileOperationPattern("**/")
        folderFilesPattern.setMatches(lsp4j.FileOperationPatternKind.Folder)
        val fileOperationOptions = new lsp4j.FileOperationOptions(
          List(
            new lsp4j.FileOperationFilter(scalaFilesPattern),
            new lsp4j.FileOperationFilter(folderFilesPattern),
          ).asJava
        )
        val fileOperationsServerCapabilities =
          new lsp4j.FileOperationsServerCapabilities()
        fileOperationsServerCapabilities.setWillRename(fileOperationOptions)
        val workspaceCapabilitiesOptions = new lsp4j.WorkspaceFoldersOptions()
        workspaceCapabilitiesOptions.setSupported(true)
        workspaceCapabilitiesOptions.setChangeNotifications(true)
        val workspaceCapabilities =
          new lsp4j.WorkspaceServerCapabilities(workspaceCapabilitiesOptions)
        workspaceCapabilities.setFileOperations(
          fileOperationsServerCapabilities
        )
        capabilities.setWorkspace(workspaceCapabilities)

        capabilities.setTextDocumentSync(textDocumentSyncOptions)

        val gson = new Gson
        val data =
          gson.toJsonTree(MetalsExperimental())
        capabilities.setExperimental(data)
        val serverInfo = new lsp4j.ServerInfo("Metals", BuildInfo.metalsVersion)
        new lsp4j.InitializeResult(capabilities, serverInfo)
      })
      .asJava
  }

  def cancel(): Unit = {
    cancelables.cancel()
    folderServices.foreach(_.cancel())
  }

  def initialized(): Future[Unit] = {
    statusBar.start(sh, 0, 1, ju.concurrent.TimeUnit.SECONDS)
    workDoneProgress.start(sh, 0, 1, ju.concurrent.TimeUnit.SECONDS)
    for {
      _ <- userConfigSync.initSyncUserConfiguration(folderServices)
      _ <- Future(startHttpServer())
      _ <- Future.sequence(folderServices.map(_.initialized()))
    } yield ()
  }

  private def startHttpServer(): Unit = {
    if (clientConfig.isHttpEnabled()) {
      val host = "localhost"
      val port = 5031
      var url = s"http://$host:$port"
      var render: () => String = () => ""
      var completeCommand: HttpServerExchange => Unit = (_) => ()
      def getTestyForURI(uri: URI) =
        getServiceFor(uri.toAbsolutePath).getTastyForURI(uri)
      val server = register(
        MetalsHttpServer(
          host,
          port,
          () => render(),
          e => completeCommand(e),
          () => doctor.problemsHtmlPage(url),
          getTestyForURI,
          this,
        )
      )
      httpServer = Some(server)
      val newClient = new MetalsHttpClient(
        folders.map(_.path),
        () => url,
        languageClient.underlying,
        () => server.reload(),
        clientConfig.icons(),
      )
      render = () => newClient.renderHtml
      completeCommand = e => newClient.completeCommand(e)
      languageClient.underlying = newClient
      server.start()
      url = server.address
    }
  }

  lazy val shutdownPromise =
    new ju.concurrent.atomic.AtomicReference[Promise[Unit]](null)

  def shutdown(): CompletableFuture[Unit] = {
    val promise = Promise[Unit]()
    // Ensure we only run `shutdown` at most once and that `exit` waits for the
    // `shutdown` promise to complete.
    if (shutdownPromise.compareAndSet(null, promise)) {
      scribe.info("shutting down Metals")
      try {
        folderServices.foreach(_.onShutdown())
      } catch {
        case NonFatal(e) =>
          scribe.error("cancellation error", e)
      } finally {
        promise.success(())
      }
      if (clientConfig.isExitOnShutdown()) {
        System.exit(0)
      }
      promise.future.asJava
    } else {
      shutdownPromise.get().future.asJava
    }
  }

  def exit(): Unit = {
    // `shutdown` is idempotent, we can trigger it as often as we like.
    shutdown()
    // Ensure that `shutdown` has completed before killing the process.
    // Some clients may send `exit` immediately after `shutdown` causing
    // the build server to get killed before it can clean up resources.
    try {
      Await.result(
        shutdownPromise.get().future,
        Duration(3, ju.concurrent.TimeUnit.SECONDS),
      )
    } catch {
      case NonFatal(e) =>
        scribe.error("shutdown error", e)
    } finally {
      System.exit(0)
    }
  }

  def workspaceSymbol(query: String): Seq[lsp4j.SymbolInformation] =
    folderServices.flatMap(_.workspaceSymbol(query))

  private def maybeResetWorkspace(): Future[Unit] = {
    languageClient
      .showMessageRequest(Messages.ResetWorkspace.params())
      .asScala
      .flatMap { response =>
        if (response != null)
          response.getTitle match {
            case Messages.ResetWorkspace.resetWorkspace =>
              Future
                .sequence(folderServices.map(_.resetWorkspace()))
                .ignoreValue
            case _ => Future.unit
          }
        else {
          Future.unit
        }
      }
      .recover { e =>
        scribe.warn("Error requesting workspace reset", e)
      }
  }

}

class Folder(
    val path: AbsolutePath,
    val visibleName: Option[String],
    isKnownMetalsProject: Boolean,
) {

  lazy val isMetalsProject: Boolean =
    isKnownMetalsProject || path.resolve(".metals").exists || path
      .isMetalsProject()

  /**
   * A workspace folder might be a project reference for an other project.
   * In that case all its commands will be delegated to the main project's service.
   * We keep the path to main project's root in a dedicated setting, so even
   * before the main project is imported, this folder is known to be a reference.
   */
  lazy val optDelegatePath: Option[AbsolutePath] =
    DelegateSetting.readDeleteSetting(path)

  def projectReferences: List[AbsolutePath] =
    DelegateSetting.readProjectRefs(path)

  def nameOrUri: String = visibleName.getOrElse(path.toString())
}

object Folder {
  def unapply(f: Folder): Option[(AbsolutePath, Option[String])] = Some(
    (f.path, f.visibleName)
  )
  def apply(
      folder: lsp4j.WorkspaceFolder,
      isKnownMetalsProject: Boolean,
  ): Folder = {
    val name = Option(folder.getName()) match {
      case Some("") => None
      case maybeValue => maybeValue
    }
    new Folder(folder.getUri().toAbsolutePath, name, isKnownMetalsProject)
  }
}
