package scala.meta.internal.metals

import java.util.concurrent.CompletableFuture
import java.{util => ju}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.DidFocusResult
import scala.meta.internal.metals.HoverExtParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLspService
import scala.meta.internal.metals.WindowStateDidChangeParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.doctor.DoctorVisibilityDidChangeParams
import scala.meta.internal.metals.findfiles.FindTextInDependencyJarsRequest
import scala.meta.internal.pc
import scala.meta.internal.tvp.MetalsTreeViewChildrenResult
import scala.meta.internal.tvp.TreeViewChildrenParams
import scala.meta.internal.tvp.TreeViewNodeCollapseDidChangeParams
import scala.meta.internal.tvp.TreeViewNodeRevealResult
import scala.meta.internal.tvp.TreeViewParentParams
import scala.meta.internal.tvp.TreeViewParentResult
import scala.meta.internal.tvp.TreeViewVisibilityDidChangeParams
import scala.meta.io.AbsolutePath
import scala.meta.metals.lsp.ScalaLspService

import com.google.gson.Gson
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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.logging.LanguageClientLogger
import com.google.gson.JsonNull
import scala.meta.internal.metals.doctor.Doctor

class WorkspaceLspService(
    ec: ExecutionContextExecutorService,
    sh: ju.concurrent.ScheduledExecutorService,
    serverInputs: MetalsServerInputs,
    client: MetalsLanguageClient,
    initializeParams: lsp4j.InitializeParams,
    val folders: List[(String, AbsolutePath)],
) extends ScalaLspService {
  import serverInputs._
  implicit val ex: ExecutionContext = ec

  private val clientConfig =
    ClientConfiguration(
      serverInputs.initialServerConfig,
      initializeParams,
    )

  private val languageClient = {
    val languageClient = new ConfiguredLanguageClient(client, clientConfig)
    // Set the language client so that we can forward log messages to the client
    LanguageClientLogger.languageClient = Some(languageClient)
    // TODO:: cancelables.add(() => languageClient.shutdown())
    languageClient
  }

  @volatile
  private var userConfig: UserConfiguration = initialUserConfig

  val folderServices = folders
    .withFilter { case (_, uri) =>
      !BuildTools.default(uri).isEmpty
    }
    .map { case (name, uri) =>
      new MetalsLspService(
        ec,
        sh,
        serverInputs,
        languageClient,
        initializeParams,
        clientConfig,
        () => userConfig,
        statusBar,
        uri,
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

  private val doctor: Doctor =
    new Doctor(
      ???,
      languageClient,
      clientConfig,
    )

  def getServiceFor(uri: String): MetalsLspService = {
    val folder =
      for {
        path <- uri.toAbsolutePathSafe
        workSpaceFolder <- folderServices
          .filter(service => path.toNIO.startsWith(service.workspace.toNIO))
          .sortBy(_.workspace.toNIO.getNameCount())
          .lastOption
      } yield workSpaceFolder
    folder.getOrElse(folderServices.head) // fallback to the first one
  }

  def getServiceForName(
      workspaceFolder: String
  ): Option[MetalsLspService] =
    for {
      workSpaceFolder <- folderServices
        .find(service => service.workspaceId == workspaceFolder)
    } yield workSpaceFolder

  override def didOpen(
      params: DidOpenTextDocumentParams
  ): CompletableFuture[Unit] =
    getServiceFor(params.getTextDocument().getUri()).didOpen(params)

  override def didChange(
      params: DidChangeTextDocumentParams
  ): CompletableFuture[Unit] =
    getServiceFor(params.getTextDocument().getUri()).didChange(params)

  override def didClose(params: DidCloseTextDocumentParams): Unit =
    getServiceFor(params.getTextDocument().getUri()).didClose(params)

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
    ???

  override def willRenameFiles(
      params: RenameFilesParams
  ): CompletableFuture[WorkspaceEdit] = ???

  override def didChangeConfiguration(
      params: DidChangeConfigurationParams
  ): CompletableFuture[Unit] = {
    val fullJson = params.getSettings.asInstanceOf[JsonElement].getAsJsonObject
    val metalsSection =
      Option(fullJson.getAsJsonObject("metals")).getOrElse(new JsonObject)

    updateConfiguration(metalsSection).asJava
  }

  private def updateConfiguration(json: JsonObject): Future[Unit] = {
    ???
    // UserConfiguration.fromJson(json, clientConfig) match {
    //   case Left(errors) =>
    //     errors.foreach { error => scribe.error(s"config error: $error") }
    //     Future.successful(())
    //   case Right(newUserConfig) =>
    //     val old = userConfig
    //     userConfig = newUserConfig
    //     if (userConfig.excludedPackages != old.excludedPackages) {
    //       excludedPackageHandler =
    //         ExcludedPackagesHandler.fromUserConfiguration(
    //           userConfig.excludedPackages.getOrElse(Nil)
    //         )
    //       workspaceSymbols.indexClasspath()
    //     }

    //     userConfig.fallbackScalaVersion.foreach { version =>
    //       if (!ScalaVersions.isSupportedAtReleaseMomentScalaVersion(version)) {
    //         val params =
    //           Messages.UnsupportedScalaVersion.fallbackScalaVersionParams(
    //             version
    //           )
    //         languageClient.showMessage(params)
    //       }
    //     }

    //     if (userConfig.symbolPrefixes != old.symbolPrefixes) {
    //       compilers.restartAll()
    //     }

    //     val resetDecorations =
    //       if (
    //         userConfig.showImplicitArguments != old.showImplicitArguments ||
    //         userConfig.showImplicitConversionsAndClasses != old.showImplicitConversionsAndClasses ||
    //         userConfig.showInferredType != old.showInferredType
    //       ) {
    //         buildServerPromise.future.flatMap { _ =>
    //           syntheticsDecorator.refresh()
    //         }
    //       } else {
    //         Future.successful(())
    //       }

    //     val restartBuildServer = bspSession
    //       .map { session =>
    //         if (session.main.isBloop) {
    //           bloopServers
    //             .ensureDesiredVersion(
    //               userConfig.currentBloopVersion,
    //               session.version,
    //               userConfig.bloopVersion.nonEmpty,
    //               old.bloopVersion.isDefined,
    //               () => autoConnectToBuildServer,
    //             )
    //             .flatMap { _ =>
    //               bloopServers.ensureDesiredJvmSettings(
    //                 userConfig.bloopJvmProperties,
    //                 userConfig.javaHome,
    //                 () => autoConnectToBuildServer(),
    //               )
    //             }
    //         } else if (
    //           userConfig.ammoniteJvmProperties != old.ammoniteJvmProperties && buildTargets.allBuildTargetIds
    //             .exists(Ammonite.isAmmBuildTarget)
    //         ) {
    //           languageClient
    //             .showMessageRequest(Messages.AmmoniteJvmParametersChange.params())
    //             .asScala
    //             .flatMap {
    //               case item if item == Messages.AmmoniteJvmParametersChange.restart =>
    //                 ammonite.reload()
    //               case _ =>
    //                 Future.successful(())
    //             }
    //         } else {
    //           Future.successful(())
    //         }
    //       }
    //       .getOrElse(Future.successful(()))
    //     Future.sequence(List(restartBuildServer, resetDecorations)).map(_ => ())
  }

  override def didChangeWatchedFiles(
      params: DidChangeWatchedFilesParams
  ): CompletableFuture[Unit] = ???

  override def treeViewChildren(
      params: TreeViewChildrenParams
  ): CompletableFuture[MetalsTreeViewChildrenResult] = ???

  override def treeViewParent(
      params: TreeViewParentParams
  ): CompletableFuture[TreeViewParentResult] = ???

  override def treeViewReveal(
      params: TextDocumentPositionParams
  ): CompletableFuture[TreeViewNodeRevealResult] = ???

  override def findTextInDependencyJars(
      params: FindTextInDependencyJarsRequest
  ): CompletableFuture[ju.List[Location]] = ???

  override def doctorVisibilityDidChange(
      params: DoctorVisibilityDidChangeParams
  ): CompletableFuture[Unit] = ???

  override def treeViewVisibilityDidChange(
      params: TreeViewVisibilityDidChangeParams
  ): CompletableFuture[Unit] = ???

  override def treeViewNodeCollapseDidChange(
      params: TreeViewNodeCollapseDidChangeParams
  ): CompletableFuture[Unit] = ???

  override def didFocus(
      params: AnyRef
  ): CompletableFuture[DidFocusResult.Value] = ???

  override def windowStateDidChange(params: WindowStateDidChangeParams): Unit =
    ???

  def foreachSeq[A](
      f: MetalsLspService => Future[A],
      ignoreValue: Boolean = false,
  ): CompletableFuture[Object] = {
    val res = Future.sequence(folderServices.map(f))
    if (ignoreValue) res.ignoreValue.asJavaObject
    else res.asJavaObject
  }

  def collectSeq[A, B](f: MetalsLspService => Future[A])(
      compose: List[A] => B
  ): Future[B] =
    Future.sequence(folderServices.map(f)).collect { case v => compose(v) }

  override def executeCommand(
      params: ExecuteCommandParams
  ): CompletableFuture[Object] =
    params match {
      case ServerCommands.ScanWorkspaceSources() =>
        foreachSeq(_.indexSources(), ignoreValue = true)
      case ServerCommands.RestartBuildServer() =>
        foreachSeq(_.restartBuildServer())
      case ServerCommands.GenerateBspConfig() =>
        foreachSeq(_.generateBspConfig(), ignoreValue = true)
      case ServerCommands.ImportBuild() =>
        foreachSeq(_.slowConnectToBuildServer(forceImport = true))
      case ServerCommands.ConnectBuildServer() =>
        foreachSeq(_.quickConnectToBuildServer())
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
        Future {
          doctor.onVisibilityDidChange(true)
          doctor.executeRunDoctor()
        }.asJavaObject
      case ServerCommands.ListBuildTargets() =>
        Future { // TODO:: this probably should be per folder
          folderServices
            .flatMap(
              _.buildTargets.all.toList
                .map(_.getDisplayName())
                .sorted
            )
            .asJava
        }.asJavaObject
      //   case ServerCommands.BspSwitch() =>
      //     (for {
      //       isSwitched <- bspConnector.switchBuildServer(
      //         workspace,
      //         () => slowConnectToBuildServer(forceImport = true),
      //       )
      //       _ <- {
      //         if (isSwitched) quickConnectToBuildServer()
      //         else Future.successful(())
      //       }
      //     } yield ()).asJavaObject
      //   case ServerCommands.OpenIssue() =>
      //     Future
      //       .successful(Urls.openBrowser(githubNewIssueUrlCreator.buildUrl()))
      //       .asJavaObject
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
      //   case ServerCommands.GotoPosition(location) =>
      //     Future {
      //       languageClient.metalsExecuteClientCommand(
      //         ClientCommands.GotoLocation.toExecuteCommandParams(
      //           ClientCommands.WindowLocation(
      //             location.getUri(),
      //             location.getRange(),
      //           )
      //         )
      //       )
      //     }.asJavaObject

      //   case ServerCommands.GotoSymbol(symbol) =>
      //     Future {
      //       for {
      //         location <- definitionProvider
      //           .fromSymbol(symbol, focusedDocument)
      //           .asScala
      //           .headOption
      //       } {
      //         languageClient.metalsExecuteClientCommand(
      //           ClientCommands.GotoLocation.toExecuteCommandParams(
      //             ClientCommands.WindowLocation(
      //               location.getUri(),
      //               location.getRange(),
      //             )
      //           )
      //         )
      //       }
      //     }.asJavaObject
      //   case ServerCommands.GotoLog() =>
      //     Future {
      //       val log = workspace.resolve(Directories.log)
      //       val linesCount = log.readText.linesIterator.size
      //       val pos = new l.Position(linesCount, 0)
      //       val location = new Location(
      //         log.toURI.toString(),
      //         new l.Range(pos, pos),
      //       )
      //       languageClient.metalsExecuteClientCommand(
      //         ClientCommands.GotoLocation.toExecuteCommandParams(
      //           ClientCommands.WindowLocation(
      //             location.getUri(),
      //             location.getRange(),
      //           )
      //         )
      //       )
      //     }.asJavaObject

      //   case ServerCommands.StartDebugAdapter(params) if params.getData != null =>
      //     debugProvider
      //       .ensureNoWorkspaceErrors(params.getTargets.asScala.toSeq)
      //       .flatMap(_ => debugProvider.asSession(params))
      //       .asJavaObject

      //   case ServerCommands.StartMainClass(params) if params.mainClass != null =>
      //     debugProvider
      //       .resolveMainClassParams(params)
      //       .flatMap(debugProvider.asSession)
      //       .asJavaObject

      //   case ServerCommands.StartTestSuite(params)
      //       if params.target != null && params.requestData != null =>
      //     debugProvider
      //       .resolveTestSelectionParams(params)
      //       .flatMap(debugProvider.asSession)
      //       .asJavaObject

      //   case ServerCommands.ResolveAndStartTestSuite(params)
      //       if params.testClass != null =>
      //     debugProvider
      //       .resolveTestClassParams(params)
      //       .flatMap(debugProvider.asSession)
      //       .asJavaObject

      //   case ServerCommands.StartAttach(params) if params.hostName != null =>
      //     debugProvider
      //       .resolveAttachRemoteParams(params)
      //       .flatMap(debugProvider.asSession)
      //       .asJavaObject

      //   case ServerCommands.DiscoverAndRun(params) =>
      //     debugProvider
      //       .debugDiscovery(params)
      //       .flatMap(debugProvider.asSession)
      //       .asJavaObject

      //   case ServerCommands.AnalyzeStacktrace(content) =>
      //     Future {
      //       val command = stacktraceAnalyzer.analyzeCommand(content)
      //       command.foreach(languageClient.metalsExecuteClientCommand)
      //       scribe.debug(s"Executing AnalyzeStacktrace ${command}")
      //     }.asJavaObject

      //   case ServerCommands.GotoSuperMethod(textDocumentPositionParams) =>
      //     Future {
      //       val command =
      //         supermethods.getGoToSuperMethodCommand(textDocumentPositionParams)
      //       command.foreach(languageClient.metalsExecuteClientCommand)
      //       scribe.debug(s"Executing GoToSuperMethod ${command}")
      //     }.asJavaObject

      //   case ServerCommands.SuperMethodHierarchy(textDocumentPositionParams) =>
      //     scribe.debug(s"Executing SuperMethodHierarchy ${params.getCommand()}")
      //     supermethods
      //       .jumpToSelectedSuperMethod(textDocumentPositionParams)
      //       .asJavaObject

      //   case ServerCommands.ResetChoicePopup() =>
      //     val argsMaybe = Option(params.getArguments())
      //     (argsMaybe.flatMap(_.asScala.headOption) match {
      //       case Some(arg: JsonPrimitive) =>
      //         val value = arg.getAsString().replace("+", " ")
      //         scribe.debug(
      //           s"Executing ResetChoicePopup ${params.getCommand()} for choice ${value}"
      //         )
      //         popupChoiceReset.reset(value)
      //       case _ =>
      //         scribe.debug(
      //           s"Executing ResetChoicePopup ${params.getCommand()} in interactive mode."
      //         )
      //         popupChoiceReset.interactiveReset()
      //     }).asJavaObject

      //   case ServerCommands.ResetNotifications() =>
      //     Future {
      //       tables.dismissedNotifications.resetAll()
      //     }.asJavaObject

      //   case ServerCommands.NewScalaFile(args) =>
      //     val directoryURI = args.lift(0).flatten.map(new URI(_))
      //     val name = args.lift(1).flatten
      //     val fileType = args.lift(2).flatten
      //     newFileProvider
      //       .handleFileCreation(directoryURI, name, fileType, isScala = true)
      //       .asJavaObject

      //   case ServerCommands.NewJavaFile(args) =>
      //     val directoryURI = args.lift(0).flatten.map(new URI(_))
      //     val name = args.lift(1).flatten
      //     val fileType = args.lift(2).flatten
      //     newFileProvider
      //       .handleFileCreation(directoryURI, name, fileType, isScala = false)
      //       .asJavaObject

      //   case ServerCommands.StartAmmoniteBuildServer() =>
      //     ammonite.start().asJavaObject
      //   case ServerCommands.StopAmmoniteBuildServer() =>
      //     ammonite.stop()

      //   case ServerCommands.StartScalaCliServer() =>
      //     val f = focusedDocument match {
      //       case None => Future.unit
      //       case Some(path) =>
      //         val scalaCliPath = scalaCliDirOrFile(path)
      //         if (scalaCli.loaded(scalaCliPath)) Future.unit
      //         else scalaCli.start(scalaCliPath)
      //     }
      //     f.asJavaObject
      //   case ServerCommands.StopScalaCliServer() =>
      //     scalaCli.stop()

      //   case ServerCommands.NewScalaProject() =>
      //     newProjectProvider.createNewProjectFromTemplate().asJavaObject

      //   case ServerCommands.CopyWorksheetOutput(path) =>
      //     val worksheetPath = path.toAbsolutePath
      //     val output = worksheetProvider.copyWorksheetOutput(worksheetPath)

      //     if (output.nonEmpty) {
      //       Future(output).asJavaObject
      //     } else {
      //       languageClient.showMessage(Messages.Worksheets.unableToExport)
      //       Future.successful(()).asJavaObject
      //     }
      //   case actionCommand
      //       if codeActionProvider.allActionCommandsIds(
      //         actionCommand.getCommand()
      //       ) =>
      //     val getOptDisplayableMessage: PartialFunction[Throwable, String] = {
      //       case e: DisplayableException => e.getMessage()
      //       case e: Exception if (e.getCause() match {
      //             case _: DisplayableException => true
      //             case _ => false
      //           }) =>
      //         e.getCause().getMessage()
      //     }
      //     CancelTokens.future { token =>
      //       codeActionProvider
      //         .executeCommands(params, token)
      //         .recover(
      //           getOptDisplayableMessage andThen (languageClient
      //             .showMessage(l.MessageType.Info, _))
      //         )
      //         .withObjectValue
      //     }
      //   case cmd =>
      //     ServerCommands.all
      //       .find(command => command.id == cmd.getCommand())
      //       .fold {
      //         scribe.error(s"Unknown command '$cmd'")
      //       } { foundCommand =>
      //         scribe.error(
      //           s"Expected '${foundCommand.arguments}', but got '${cmd.getArguments()}'"
      //         )
      //       }
      case _ =>
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
            (ServerCommands.allIds ++
              // TODO:: we probably want to make id of workspace a part of the command id
              folderServices.flatMap(_.allActionCommandsIds())).toList.asJava
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

  def cancel(): Unit = folderServices.foreach(_.cancel())

  def initialized(): Future[Unit] = {
    statusBar.start(sh, 0, 1, ju.concurrent.TimeUnit.SECONDS)
    folderServices.foreach { service =>
      service.registerNiceToHaveFilePatterns()
      service.connectTables()
    }
    syncUserconfiguration().flatMap { _ =>
      Future
        .sequence(folderServices.map(_.initialized()))
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
