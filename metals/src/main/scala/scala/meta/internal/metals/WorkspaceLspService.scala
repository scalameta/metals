package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLspService
import scala.meta.internal.metals.WindowStateDidChangeParams
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.metals.logging.LanguageClientLogger
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
import scala.meta.pc.DisplayableException

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
import org.eclipse.lsp4j.jsonrpc.messages

class WorkspaceLspService(
    ec: ExecutionContextExecutorService,
    sh: ju.concurrent.ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    client: MetalsLanguageClient,
    initializeParams: lsp4j.InitializeParams,
    val folders: List[Folder],
) extends ScalaLspService {
  import serverInputs._
  implicit val ex: ExecutionContextExecutorService = ec
  private val cancelables = new MutableCancelable()

  private val clientConfig =
    ClientConfiguration(
      serverInputs.initialServerConfig,
      initializeParams,
    )

  private val languageClient = {
    val languageClient = new ConfiguredLanguageClient(client, clientConfig)
    // Set the language client so that we can forward log messages to the client
    LanguageClientLogger.languageClient = Some(languageClient)
    cancelables.add(() => languageClient.shutdown())
    languageClient
  }

  @volatile
  private var userConfig: UserConfiguration = initialUserConfig

  private val shellRunner = register {
    new ShellRunner(languageClient, () => userConfig, time, statusBar)
  }

  var focusedDocument: Option[AbsolutePath] = None
  private val recentlyFocusedFiles = new ActiveFiles(time)

  val folderServices: List[MetalsLspService] = folders
    .withFilter { case Folder(uri, _, _) =>
      !BuildTools.default(uri).isEmpty
    }
    .map { case Folder(uri, id, name) =>
      new MetalsLspService(
        ec,
        sh,
        serverInputs,
        languageClient,
        initializeParams,
        clientConfig,
        () => userConfig,
        statusBar,
        () => focusedDocument,
        shellRunner,
        timerProvider,
        initTreeView,
        uri,
        id,
        name,
      )
    }

  assert(folderServices.nonEmpty)

  val statusBar: StatusBar = new StatusBar(
    languageClient,
    time,
    progressTicks,
    clientConfig,
  )

  private val timerProvider: TimerProvider = new TimerProvider(time)

  val treeView: TreeViewProvider =
    if (clientConfig.isTreeViewProvider) {
      new MetalsTreeViewProvider(
        folderServices.map(_.treeView),
        languageClient,
        sh,
      )
    } else NoopTreeViewProvider

  def initTreeView(): Unit = treeView.init()

  private val newProjectProvider: NewProjectProvider = new NewProjectProvider(
    languageClient,
    statusBar,
    clientConfig,
    shellRunner,
    clientConfig.icons,
    folders.head.uri,
  )

  private val githubNewIssueUrlCreator = new GithubNewIssueUrlCreator(
    folderServices.map(_.gitHubIssueFolderInfo),
    initializeParams.getClientInfo(),
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

  def getServiceFor(path: AbsolutePath): MetalsLspService = {
    val folder =
      for {
        workSpaceFolder <- folderServices
          .filter(service => path.toNIO.startsWith(service.folder.toNIO))
          .sortBy(_.folder.toNIO.getNameCount())
          .lastOption
      } yield workSpaceFolder
    folder.getOrElse(folderServices.head) // fallback to the first one
  }

  def getServiceFor(uri: String): MetalsLspService = {
    val folder =
      for {
        path <- uri.toAbsolutePathSafe
      } yield getServiceFor(path)
    folder.getOrElse(folderServices.head) // fallback to the first one
  }

  def onCurrentFolder(
      f: MetalsLspService => Future[Unit],
      actionName: String,
  ): Future[Unit] = {
    def currentService(): Future[Option[MetalsLspService]] =
      folderServices match {
        case head :: Nil => Future { Some(head) }
        case _ =>
          focusedDocument.map(getServiceFor) match {
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
        Future.successful(())
    }
  }

  def getServiceForName(
      workspaceFolder: String
  ): Option[MetalsLspService] =
    for {
      workSpaceFolder <- folderServices
        .find(service => service.folderId == workspaceFolder)
    } yield workSpaceFolder

  def getServiceForExactUri(
      folderUri: String
  ): Option[MetalsLspService] =
    for {
      workSpaceFolder <- folderServices
        .find(service => service.folder.toString == folderUri)
    } yield workSpaceFolder

  def foreachSeq[A](
      f: MetalsLspService => Future[A],
      ignoreValue: Boolean = false,
      withF: Option[() => Unit] = None,
  ): CompletableFuture[Object] = {
    val res0 = Future.sequence(folderServices.map(f))
    val res = withF match {
      case Some(f) => res0.andThen { case Success(_) => f() }
      case None => res0
    }
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
    focusedDocument.foreach(recentlyFocusedFiles.add)
    focusedDocument = Some(params.getTextDocument.getUri.toAbsolutePath)
    getServiceFor(params.getTextDocument().getUri()).didOpen(params)
  }

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] =
    getServiceFor(params.getTextDocument().getUri()).didChange(params)

  override def didClose(params: DidCloseTextDocumentParams): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    if (focusedDocument.contains(path)) {
      focusedDocument = recentlyFocusedFiles.pollRecent()
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
  ): CompletableFuture[CompletionItem] = {
    val res =
      for {
        data <- item.data
        service <- getServiceForName(data.workspaceId)
      } yield service.completionItemResolve(item)
    res.getOrElse(Future.successful(item).asJava)
  }

  override def signatureHelp(
      params: TextDocumentPositionParams
  ): CompletableFuture[SignatureHelp] =
    getServiceFor(params.getTextDocument.getUri).signatureHelp(params)

  override def codeAction(
      params: CodeActionParams
  ): CompletableFuture[ju.List[CodeAction]] =
    getServiceFor(params.getTextDocument.getUri).codeAction(params)

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
  ): CompletableFuture[ju.List[SymbolInformation]] =
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
        if (newPath.startWith(service.folder))
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
  ): CompletableFuture[Unit] = {
    val fullJson = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
    val metalsSection =
      Option(fullJson.getAsJsonObject("metals")).getOrElse(new JsonObject)

    updateConfiguration(metalsSection).asJava
  }

  private def updateConfiguration(json: JsonObject): Future[Unit] =
    UserConfiguration.fromJson(json, clientConfig) match {
      case Left(errors) =>
        errors.foreach { error => scribe.error(s"config error: $error") }
        Future.successful(())
      case Right(newUserConfig) =>
        val old = userConfig
        userConfig = newUserConfig
        collectSeq(_.onUserConfigUpdate(old))(_ => ())
    }

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
            val uri = event.getUri()
            (uri.toAbsolutePath, getServiceFor(uri))
          })
          .groupBy(_._2)
          .map { case (service, paths) =>
            service.didChangeWatchedFiles(paths.map(_._1))
          }
      )
      .ignoreValue
      .asJava

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

  override def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] =
    onCurrentFolder(
      _.doctorVisibilityDidChange(params),
      "change doctor visibility",
    ).asJava

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
        focusedDocument = Some(uri.toAbsolutePath)
        getServiceFor(uri).didFocus(uri)
      case None =>
        CompletableFuture.completedFuture(DidFocusResult.NoBuildTarget)
    }
  }

  // def withTreeViewInit() {}

  override def windowStateDidChange(params: WindowStateDidChangeParams): Unit =
    if (params.focused) {
      folderServices.foreach(_.unpause())
    } else {
      folderServices.foreach(_.pause())
    }

  private def notFound(objectName: String, id: String) =
    languageClient.showMessage(
      Messages.errorMessageParams(s"$objectName not found: $id")
    )

  private def definedInMultipleWorkspaceFolders(
      objectName: String,
      id: String,
  ) =
    languageClient.showMessage(
      Messages.errorMessageParams(
        s"$objectName defined in multiple workspace folders: $id"
      )
    )

  override def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] =
    params match {
      case ServerCommands.ScanWorkspaceSources() =>
        foreachSeq(_.indexSources(), ignoreValue = true)
      case ServerCommands.RestartBuildServer() =>
        folderServices.find(_.isBloop()).foreach(_.shutDownBloop())
        foreachSeq(_.autoConnectToBuildServer(), withF = Some(initTreeView))
      case ServerCommands.GenerateBspConfig() =>
        foreachSeq(
          _.generateBspConfig(),
          ignoreValue = true,
          withF = Some(initTreeView),
        )
      case ServerCommands.ImportBuild() =>
        foreachSeq(
          _.slowConnectToBuildServer(forceImport = true),
          withF = Some(initTreeView),
        )
      case ServerCommands.ConnectBuildServer() =>
        foreachSeq(_.quickConnectToBuildServer(), withF = Some(initTreeView))
      case ServerCommands.DisconnectBuildServer() =>
        foreachSeq(_.disconnectOldBuildServer(), ignoreValue = true)
      case ServerCommands.DecodeFile(uri) =>
        getServiceFor(uri).decodeFile(uri).asJavaObject
      case ServerCommands.DiscoverTestSuites(params) =>
        getServiceFor(params.uri).discoverTestSuites(params.uri).asJavaObject
      case ServerCommands.DiscoverMainClasses(unresolvedParams) =>
        getServiceFor(unresolvedParams.path)
          .discoverMainClasses(unresolvedParams)
          .asJavaObject
      case ServerCommands.RunScalafix(params) =>
        val uri = params.getTextDocument().getUri()
        getServiceFor(uri).runScalafix(uri).asJavaObject
      case ServerCommands.ChooseClass(params) =>
        val uri = params.textDocument.getUri()
        getServiceFor(uri).chooseClass(uri, params.kind == "class").asJavaObject
      case ServerCommands.RunDoctor() =>
        onCurrentFolder(_.rundoctor(), "run doctor").asJavaObject
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
        foreachSeq(_.switchBspServer(), withF = Some(initTreeView))
      case ServerCommands.OpenIssue() =>
        Future
          .successful(Urls.openBrowser(githubNewIssueUrlCreator.buildUrl()))
          .asJavaObject
      case OpenBrowserCommand(url) =>
        Future.successful(Urls.openBrowser(url)).asJavaObject
      case ServerCommands.CascadeCompile() =>
        foreachSeq(_.cascadeCompile(), ignoreValue = true)
      case ServerCommands.CleanCompile() =>
        foreachSeq(_.cleanCompile(), ignoreValue = true)
      case ServerCommands.CancelCompile() =>
        foreachSeq(_.cancelCompile(), ignoreValue = true)
      case ServerCommands.PresentationCompilerRestart() =>
        foreachSeq(_.restartCompiler(), ignoreValue = true)
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
              val log = service.folder.resolve(Directories.log)
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
          "go to log",
        ).asJavaObject

      case ServerCommands.StartDebugAdapter(params) if params.getData != null =>
        folderServices.find(s =>
          params.getTargets().asScala.forall(s.supportsBuildTarget(_).isDefined)
        ) match {
          case Some(service) => service.startDebugProvider(params).asJavaObject
          case None =>
            // TODO:: show error message
            Future.successful(()).asJavaObject
        }
      case ServerCommands.StartMainClass(params) if params.mainClass != null =>
        Future
          .sequence(
            folderServices.map(_.findMainClassAndItsBuildTarget(params))
          )
          .flatMap { list =>
            list.filter(_._2.isEmpty) match {
              case (service, buildTargets) :: Nil =>
                service.startMainClass(buildTargets, params)
              case list =>
                if (list.isEmpty) notFound("Main class", params.mainClass)
                else
                  definedInMultipleWorkspaceFolders(
                    "Main class",
                    params.mainClass,
                  )
                Future.successful(())
            }
          }
          .asJavaObject

      case ServerCommands.StartTestSuite(params)
          if params.target != null && params.requestData != null =>
        val foundBuildtargets =
          folderServices
            .map(s => (s, s.supportsBuildTarget(params.target)))
            .collect { case (s, Some(b)) => (s, b) }
        foundBuildtargets match {
          case (service, target) :: Nil =>
            service.startTestSuite(target, params).asJavaObject
          case list =>
            if (list.isEmpty) notFound("Build target", params.target.toString())
            else
              definedInMultipleWorkspaceFolders(
                "Build target",
                params.target.toString(),
              )
            Future.successful(()).asJavaObject
        }
      case ServerCommands.ResolveAndStartTestSuite(params)
          if params.testClass != null =>
        Future
          .sequence(
            folderServices.map(_.findTestClassAndItsBuildTarget(params))
          )
          .flatMap { list =>
            list.filter(_._2.isEmpty) match {
              case (service, buildTargets) :: Nil =>
                service.startTestSuiteForResolved(buildTargets, params)
              case list =>
                if (list.isEmpty) notFound("Test class", params.testClass)
                else
                  definedInMultipleWorkspaceFolders(
                    "Test class",
                    params.testClass,
                  )
                Future.successful(())
            }
          }
          .asJavaObject
      case ServerCommands.StartAttach(params) if params.hostName != null =>
        // TODO:: this should probably come with workspace name
        val foundBuildtargets =
          folderServices
            .map(s => (s, s.findBuildTargetByDisplayName(params.buildTarget)))
            .collect { case (s, Some(bt)) => (s, bt.getId()) }
        foundBuildtargets match {
          case (service, target) :: Nil =>
            service.createDebugSession(target).asJavaObject
          case list =>
            if (list.isEmpty) notFound("Build target", params.buildTarget)
            else
              definedInMultipleWorkspaceFolders(
                "Build target",
                params.buildTarget,
              )
            Future.successful(()).asJavaObject
        }
      case ServerCommands.DiscoverAndRun(params) =>
        getServiceFor(params.path).debugDiscovery(params)
      case ServerCommands.AnalyzeStacktrace(content) =>
        Future {
          // Getting the service for focused document and first one otherwise
          val service =
            focusedDocument.map(getServiceFor).getOrElse(folderServices.head)
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
        def resetPopupChoice(value: String) =
          collectSeq(_.resetPopupChoice(value))(_ => ())
        val argsMaybe = Option(params.getArguments())
        (argsMaybe.flatMap(_.asScala.headOption) match {
          case Some(arg: JsonPrimitive) =>
            val value = arg.getAsString().replace("+", " ")
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} for choice ${value}"
            )
            resetPopupChoice(value)
          case _ =>
            scribe.debug(
              s"Executing ResetChoicePopup ${params.getCommand()} in interactive mode."
            )
            PopupChoiceReset.interactiveReset(languageClient, resetPopupChoice)
        }).asJavaObject

      case ServerCommands.ResetNotifications() =>
        foreachSeq(_.resetNotifications(), ignoreValue = true)

      case ServerCommands.NewScalaFile(args) =>
        val directoryURI = args.lift(0).flatten
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        directoryURI
          .map(getServiceFor)
          .getOrElse(folderServices.head)
          .handleFileCreation(directoryURI, name, fileType, isScala = true)
      case ServerCommands.NewJavaFile(args) =>
        val directoryURI = args.lift(0).flatten
        val name = args.lift(1).flatten
        val fileType = args.lift(2).flatten
        directoryURI
          .map(getServiceFor)
          .getOrElse(folderServices.head)
          .handleFileCreation(directoryURI, name, fileType, isScala = false)
      case ServerCommands.StartAmmoniteBuildServer() =>
        foreachSeq(_.ammoniteStart(), ignoreValue = false)
      case ServerCommands.StopAmmoniteBuildServer() =>
        foreachSeq(_.ammoniteStop(), ignoreValue = false)
      case ServerCommands.StartScalaCliServer() =>
        val f = focusedDocument match {
          case None => Future.unit
          case Some(path) =>
            getServiceFor(path).startScalaCli(path)
        }
        f.asJavaObject
      case ServerCommands.StopScalaCliServer() =>
        foreachSeq(_.stopScalaCli(), ignoreValue = true)
      case ServerCommands.NewScalaProject() =>
        newProjectProvider.createNewProjectFromTemplate().asJavaObject
      case ServerCommands.CopyWorksheetOutput(path) =>
        getServiceFor(path).copyWorksheetOutput(path.toAbsolutePath)
      case actionCommand
          if folderServices.head.allActionCommandsIds(
            actionCommand.getCommand()
          ) =>
        val getOptDisplayableMessage: PartialFunction[Throwable, String] = {
          case e: DisplayableException => e.getMessage()
          case e: Exception if (e.getCause() match {
                case _: DisplayableException => true
                case _ => false
              }) =>
            e.getCause().getMessage()
        }
        val service = folderServices.find(
          _.isCorrectFolderForCodeActionCommand(params)
        ) match {
          case Some(service) => service
          case None => folderServices.head
        }
        CancelTokens.future { token =>
          service
            .executeCodeActionCommand(params, token)
            .recover(
              getOptDisplayableMessage andThen (languageClient
                .showMessage(lsp4j.MessageType.Info, _))
            )
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
        // load fingerprints from last execution
        folderServices.foreach(_.loadFingerPrints())
        val capabilities = new lsp4j.ServerCapabilities()
        capabilities.setExecuteCommandProvider(
          new lsp4j.ExecuteCommandOptions(
            (ServerCommands.allIds ++ folderServices.head.allActionCommandsIds).toList.asJava
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
            new lsp4j.CodeActionOptions(
              List(
                lsp4j.CodeActionKind.QuickFix,
                lsp4j.CodeActionKind.Refactor,
                lsp4j.CodeActionKind.SourceOrganizeImports,
              ).asJava
            )
          )
        } else {
          capabilities.setCodeActionProvider(true)
        }

        val textDocumentSyncOptions = new lsp4j.TextDocumentSyncOptions
        textDocumentSyncOptions.setChange(lsp4j.TextDocumentSyncKind.Full)
        textDocumentSyncOptions.setSave(new lsp4j.SaveOptions(true))
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
        val workspaceCapabilities = new lsp4j.WorkspaceServerCapabilities()
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
    folderServices.foreach { service =>
      service.registerNiceToHaveFilePatterns()
      service.connectTables()
    }
    syncUserconfiguration().flatMap { _ =>
      Future // TODO:: we should probably have only one http server
        //                 and remove this ---v
        .sequence(folderServices.map(_.initialized(this)))
        .andThen { case Success(_) => treeView.init() }
        .ignoreValue
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
        folderServices.foreach(_.onShutdown)
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

  private def syncUserconfiguration(): Future[Unit] = {
    val supportsConfiguration = for {
      capabilities <- Option(initializeParams.getCapabilities)
      workspace <- Option(capabilities.getWorkspace)
      out <- Option(workspace.getConfiguration())
    } yield out.booleanValue()

    if (supportsConfiguration.getOrElse(false)) {
      val item = new lsp4j.ConfigurationItem()
      item.setSection("metals")
      val params = new lsp4j.ConfigurationParams(List(item).asJava)
      languageClient
        .configuration(params)
        .asScala
        .flatMap { items =>
          items.asScala.headOption.flatMap(item =>
            Option.unless(item.isInstanceOf[JsonNull])(item)
          ) match {
            case Some(item) =>
              val json = item.asInstanceOf[JsonElement].getAsJsonObject()
              updateConfiguration(json)
            case None =>
              Future.unit
          }
        }
    } else Future.unit
  }
}
// TODO:: delete id, uri is enough
case class Folder(uri: AbsolutePath, id: String, visibleName: Option[String])
