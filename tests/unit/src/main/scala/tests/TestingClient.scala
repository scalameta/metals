package tests

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.inputs.Input
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Debug
import scala.meta.internal.metals.FileOutOfScalaCliBspScope
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.WorkspaceChoicePopup
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.clients.language.MetalsSlowTaskParams
import scala.meta.internal.metals.clients.language.MetalsSlowTaskResult
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.internal.metals.clients.language.RawMetalsInputBoxResult
import scala.meta.internal.metals.clients.language.RawMetalsQuickPickResult
import scala.meta.internal.tvp.TreeViewDidChangeParams
import scala.meta.io.AbsolutePath

import com.google.gson.JsonObject
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import tests.MetalsTestEnrichments._
import tests.TestOrderings._

/**
 * Fake LSP client that responds to notifications/requests initiated by the server.
 *
 * - Can customize how to respond to window/showMessageRequest
 * - Aggregates published diagnostics and pretty-prints them as strings
 */
class TestingClient(workspace: AbsolutePath, val buffers: Buffers)
    extends NoopLanguageClient {
  // Customization of the window/showMessageRequest response
  var importBuildChanges: MessageActionItem = ImportBuildChanges.notNow
  var importBuild: MessageActionItem = ImportBuild.notNow
  var switchBuildTool: MessageActionItem = NewBuildToolDetected.dontSwitch
  var restartBloop: MessageActionItem = BloopVersionChange.notNow
  var getDoctorInformation: MessageActionItem = CheckDoctor.moreInformation
  var selectBspServer: Seq[MessageActionItem] => MessageActionItem = { _ =>
    null
  }
  var chooseWorkspaceFolder: Seq[MessageActionItem] => MessageActionItem =
    _.head
  var chooseBuildTool: Seq[MessageActionItem] => MessageActionItem = {
    actions =>
      actions
        .find(_.getTitle == "sbt")
        .getOrElse(new MessageActionItem("fail"))
  }
  var createScalaFmtConf: MessageActionItem = null
  var chooseMainClass: Seq[MessageActionItem] => MessageActionItem = {
    actions =>
      actions.find(_.getTitle == "a.Main").get
  }
  var importScalaCliScript = new MessageActionItem(ImportScalaScript.dismiss)
  var resetWorkspace = new MessageActionItem(ResetWorkspace.cancel)
  var regenerateAndRestartScalaCliBuildSever = FileOutOfScalaCliBspScope.ignore

  val resources = new ResourceOperations(buffers)
  val diagnostics: TrieMap[AbsolutePath, Seq[Diagnostic]] =
    TrieMap.empty[AbsolutePath, Seq[Diagnostic]]
  val diagnosticsCount: TrieMap[AbsolutePath, AtomicInteger] =
    TrieMap.empty[AbsolutePath, AtomicInteger]
  val messageRequests = new ConcurrentLinkedDeque[String]()
  val showMessages = new ConcurrentLinkedQueue[MessageParams]()
  val statusParams = new ConcurrentLinkedQueue[MetalsStatusParams]()
  val workDoneProgressCreateParams =
    new ConcurrentLinkedQueue[WorkDoneProgressCreateParams]()
  val progressParams = new ConcurrentLinkedQueue[ProgressParams]()
  val logMessages = new ConcurrentLinkedQueue[MessageParams]()
  val treeViewChanges = new ConcurrentLinkedQueue[TreeViewDidChangeParams]()

  /** Stores commands executed by the client */
  val clientCommands = new ConcurrentLinkedDeque[ExecuteCommandParams]()
  val decorations =
    new ConcurrentHashMap[AbsolutePath, Set[PublishDecorationsParams]]()
  var slowTaskHandler: MetalsSlowTaskParams => Option[MetalsSlowTaskResult] = {
    _: MetalsSlowTaskParams => None
  }
  var showMessageHandler: MessageParams => Unit = { _: MessageParams =>
    ()
  }
  var showMessageRequestHandler
      : ShowMessageRequestParams => Option[MessageActionItem] = {
    _: ShowMessageRequestParams => None
  }
  var inputBoxHandler: MetalsInputBoxParams => RawMetalsInputBoxResult = {
    _: MetalsInputBoxParams => RawMetalsInputBoxResult(cancelled = true)
  }
  var quickPickHandler: MetalsQuickPickParams => RawMetalsQuickPickResult = {
    _: MetalsQuickPickParams => RawMetalsQuickPickResult(cancelled = true)
  }

  private val refreshCount = new AtomicInteger
  var refreshModelHandler: Int => Unit = (_) => ()

  val testExplorerUpdates: Promise[List[JsonObject]] =
    Promise[List[JsonObject]]()

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    Debug.printEnclosing(params.getCommand())
    clientCommands.addLast(params)
    params.getCommand match {
      case ClientCommands.RefreshModel.id =>
        refreshModelHandler(refreshCount.getAndIncrement())
      case ClientCommands.UpdateTestExplorer.id =>
        testExplorerUpdates.trySuccess(
          params.getArguments().asScala.toList.asInstanceOf[List[JsonObject]]
        )
      case _ =>
    }
  }

  override def applyEdit(
      params: ApplyWorkspaceEditParams
  ): CompletableFuture[ApplyWorkspaceEditResponse] = {
    applyWorkspaceEdit(params.getEdit())
    CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true))
  }

  private def applyWorkspaceEdit(edit: WorkspaceEdit): Unit = {
    val changes = Option(edit.getChanges())
    val documentChanges = Option(edit.getDocumentChanges())
    changes.foreach(_.forEach(applyEdits))
    documentChanges.foreach(_.forEach(dc => applyDocumentChange(dc.asScala)))
  }

  private def applyDocumentChange(
      documentChange: Either[TextDocumentEdit, ResourceOperation]
  ): Unit = {
    documentChange match {
      case Left(textDocumentEdit: TextDocumentEdit) =>
        val document = textDocumentEdit.getTextDocument
        val edits = textDocumentEdit.getEdits
        val uri = document.getUri
        applyEdits(uri, edits)
      case Right(resourceOperation: ResourceOperation) =>
        resources.applyResourceOperation(resourceOperation)
    }
  }

  private def applyEdits(
      uri: String,
      textEdits: java.util.List[TextEdit],
  ): Unit = {
    val path = AbsolutePath.fromAbsoluteUri(URI.create(uri))
    val content = buffers.get(path).getOrElse("")
    val editedContent =
      TextEdits.applyEdits(content, textEdits.asScala.toList)
    buffers.put(path, editedContent)
  }

  def workspaceClientCommands: List[String] = {
    clientCommands.asScala.toList.map(_.getCommand)
  }

  def pollStatusBar(): String = statusParams.poll().text

  def statusBarHistory: String = {
    statusParams.asScala
      .map { params =>
        if (params.show) {
          s"<show> - ${params.text}".trim
        } else if (params.hide) {
          "<hide>"
        } else {
          params.text.trim
        }
      }
      .mkString("\n")
  }
  def workspaceLogMessages: String = {
    logMessages.asScala
      .map { params => s"${params.getType}: ${params.getMessage}" }
      .mkString("\n")
  }
  def workspaceShowMessages: String = {
    showMessages.asScala.map(_.getMessage).mkString("\n")
  }
  def workspaceErrorShowMessages: String = {
    showMessages.asScala
      .filter(_.getType == MessageType.Error)
      .map(_.getMessage)
      .mkString("\n")
  }
  private def toPath(filename: String): AbsolutePath =
    TestingServer.toPath(workspace, filename, TrieMap.empty)
  def workspaceMessageRequests: String = {
    messageRequests.asScala.mkString("\n")
  }
  def pathDiagnostics(filename: String): String = {
    pathDiagnostics(toPath(filename))
  }
  def pathDiagnostics(path: AbsolutePath): String = {
    val isDeleted = !path.isFile
    val diags = diagnostics.getOrElse(path, Nil).sortBy(_.getRange)
    val relpath =
      if (path.isJarFileSystem)
        path.toString.stripPrefix("/")
      else
        path.toRelative(workspace).toURI(isDirectory = false).toString
    val input =
      if (isDeleted) {
        Input.VirtualFile(relpath + " (deleted)", "\n <deleted>" * 1000)
      } else {
        path.toInputFromBuffers(buffers).copy(path = relpath)
      }
    val sb = new StringBuilder
    diags.foreach { diag =>
      val message = diag.formatMessage(input)
      sb.append(message).append("\n")
    }
    sb.toString()
  }

  def workspaceDiagnosticsCount: String = {
    val paths = diagnosticsCount.keys.toList.sortBy(_.toURI.toString)
    paths
      .map { path =>
        s"${path.toRelative(workspace).toURI(false)}: ${diagnosticsCount(path)}"
      }
      .mkString("\n")
  }
  def workspaceDiagnostics: String = {
    val paths = diagnostics.keys.toList.sortBy(_.toURI.toString)
    paths.map(pathDiagnostics).mkString
  }

  override def registerCapability(
      params: RegistrationParams
  ): CompletableFuture[Void] =
    CompletableFutures.computeAsync { _ =>
      params.getRegistrations.asScala.foreach { registration =>
        registration.getMethod match {
          case "workspace/didChangeWatchedFiles" =>
            registration.getRegisterOptions match {
              case w: DidChangeWatchedFilesRegistrationOptions =>
                w.getWatchers.asScala.map { watcher =>
                  // TODO(olafur): Start actual file watcher.
                  watcher
                }
              case _ =>
            }
          case _ =>
        }
      }
      null
    }
  override def telemetryEvent(`object`: Any): Unit = ()
  override def publishDiagnostics(params: PublishDiagnosticsParams): Unit = {
    val path = params.getUri.toAbsolutePath
    diagnostics(path) = params.getDiagnostics.asScala.toSeq
    diagnosticsCount
      .getOrElseUpdate(path, new AtomicInteger())
      .incrementAndGet()
  }
  override def showMessage(params: MessageParams): Unit = {
    showMessageHandler(params)
    showMessages.add(params)
  }
  override def showMessageRequest(
      params: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {
    def isSameMessage(
        createParams: String => ShowMessageRequestParams
    ): Boolean = {
      BuildTools
        .default()
        .allAvailable
        .map(tool => createParams(tool.toString()))
        .contains(params)
    }

    def isNewBuildToolDetectedMessage(): Boolean = {
      val buildTools = BuildTools.default().allAvailable
      buildTools.exists(newBuildTool =>
        buildTools.exists(oldBuildTool =>
          NewBuildToolDetected.params(newBuildTool, oldBuildTool) == params
        )
      )
    }

    def choicesMessage = WorkspaceChoicePopup
      .choicesParams(
        ServerCommands.ConnectBuildServer.title.toLowerCase(),
        Nil,
      )
      .getMessage()

    CompletableFuture.completedFuture {
      messageRequests.addLast(params.getMessage)
      showMessageRequestHandler(params).getOrElse {
        if (isSameMessage(ImportBuildChanges.params)) {
          importBuildChanges
        } else if (isSameMessage(ImportBuild.params)) {
          importBuild
        } else if (BloopVersionChange.params() == params) {
          restartBloop
        } else if (CheckDoctor.isDoctor(params)) {
          getDoctorInformation
        } else if (BspSwitch.isSelectBspServer(params)) {
          selectBspServer(params.getActions.asScala.toSeq)
        } else if (params.getMessage == ChooseBuildTool.message) {
          chooseBuildTool(params.getActions.asScala.toSeq)
        } else if (MissingScalafmtConf.isCreateScalafmtConf(params)) {
          createScalaFmtConf
        } else if (params.getMessage() == MainClass.message) {
          chooseMainClass(params.getActions.asScala.toSeq)
        } else if (isNewBuildToolDetectedMessage()) {
          switchBuildTool
        } else if (ImportScalaScript.params() == params) {
          importScalaCliScript
        } else if (ResetWorkspace.params() == params) {
          resetWorkspace
        } else if (
          params
            .getMessage()
            .endsWith(
              FileOutOfScalaCliBspScope
                .askToRegenerateConfigAndRestartBspMsg("")
            )
        ) {
          regenerateAndRestartScalaCliBuildSever
        } else if (params.getMessage() == choicesMessage) {
          params.getActions().asScala.head
        } else if (
          params.getMessage() == ConnectionBspStatus
            .noResponseParams("Bill", Icons.default)
            .logMessage(Icons.default)
        ) {
          new MessageActionItem("ok")
        } else if (
          params.getMessage().startsWith("For which folder would you like to")
        ) {
          chooseWorkspaceFolder(params.getActions().asScala.toSeq)
        } else {
          throw new IllegalArgumentException(params.toString)
        }
      }
    }
  }
  override def logMessage(params: MessageParams): Unit = {
    logMessages.add(params)
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    CompletableFuture.completedFuture {
      messageRequests.addLast(params.message)
      slowTaskHandler(params) match {
        case Some(result) =>
          result
        case None =>
          MetalsSlowTaskResult(cancel = false)
      }
    }
  }

  override def metalsStatus(params: MetalsStatusParams): Unit = {
    statusParams.add(params)

  }

  override def createProgress(
      params: WorkDoneProgressCreateParams
  ): CompletableFuture[Void] = {
    CompletableFuture.completedFuture[Void] {
      workDoneProgressCreateParams.add(params)
      null
    }
  }

  override def notifyProgress(params: ProgressParams): Unit = {
    progressParams.add(params)
  }

  override def rawMetalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[RawMetalsInputBoxResult] = {
    CompletableFuture.completedFuture {
      messageRequests.addLast(params.prompt)
      inputBoxHandler(params)
    }
  }

  override def rawMetalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[RawMetalsQuickPickResult] = {
    CompletableFuture.completedFuture {
      messageRequests.addLast(params.placeHolder)
      quickPickHandler(params)
    }
  }

  override def metalsTreeViewDidChange(
      params: TreeViewDidChangeParams
  ): Unit = {
    treeViewChanges.add(params)
  }

  override def metalsPublishDecorations(
      params: PublishDecorationsParams
  ): Unit = {
    val path = params.uri.toAbsolutePath
    decorations.compute(
      path,
      {
        case (_, decorationTypes) => {
          if (decorationTypes == null) {
            Set(params)
          } else {
            decorationTypes.filter(p => p.isInline != params.isInline) + params
          }
        }
      },
    )
  }

  def syntheticDecorations: String = {
    syntheticDecorations(isHover = false)
  }

  def workspaceDecorations(filename: String): String = {
    syntheticDecorations(isHover = false, Some(filename))
  }

  def syntheticDecorationHoverMessage: String =
    syntheticDecorations(isHover = true)

  def syntheticDecorationHoverMessage(
      filename: String
  ): String = {
    syntheticDecorations(isHover = true, Some(filename))
  }
  private def syntheticDecorations(
      isHover: Boolean,
      filename: Option[String] = None,
  ): String = {
    val out = new StringBuilder()
    decorationsForPath(filename).foreach { case (path, synthetics) =>
      val input = path.toInputFromBuffers(buffers)
      input.text.linesIterator.zipWithIndex.foreach { case (line, i) =>
        val lineDecorations = synthetics.toList
          .flatMap(params =>
            params.options.map(o =>
              (
                o,
                Option(params.isInline).getOrElse(
                  false.asInstanceOf[java.lang.Boolean]
                ),
              )
            )
          )
          .filter { case (deco, _) => deco.range.getEnd().getLine() == i }
          /* Need to sort them by the type of decoration, inline needs to be first.
           * This mirrors the VS Code behaviour, the first declared type is
           * shown first if the end is the same */
          .sortBy { case (deco, isInline) =>
            (deco.range.getEnd().getCharacter(), !isInline)
          }
          .map(_._1)
        if (isHover) {
          out.append(line)
          lineDecorations.collect {
            case decoration if decoration.hoverMessage != null =>
              out.append("\n" + decoration.hoverMessage.getValue())
          }
          out.append("\n")
        } else {
          val lineIndex = lineDecorations.foldLeft(0) {
            case (index, decoration) =>
              if (decoration.renderOptions.after.contentText != null) {
                val decoCharacter = decoration.range.getEnd().getCharacter()
                out.append(line.substring(index, decoCharacter))
                out.append(decoration.renderOptions.after.contentText)
                decoCharacter
              } else {
                index
              }
          }
          out.append(line.substring(lineIndex))
          out.append("\n")
        }
      }
    }
    out.toString()
  }

  private def decorationsForPath(filename: Option[String]) = {
    filename match {
      case None =>
        decorations.asScala.find(_._2.nonEmpty)
      case Some(file) =>
        val path = workspace.resolve(file)
        val synthetics = decorations.asScala.getOrElse(path, Set.empty)
        Some(path, synthetics)
    }
  }

  def workspaceTreeViewChanges: String = {
    val changes = treeViewChanges.asScala.toSeq
      .map { change =>
        change.nodes
          .map(n => n.viewId + " " + Option(n.nodeUri).getOrElse("<root>"))
          .mkString("\n")
      }
      .sorted
      .mkString("----\n")
    treeViewChanges.clear()
    changes
  }

  def applyCodeAction(
      selectedActionIndex: Int,
      codeActions: List[CodeAction],
      server: TestingServer,
  ): Future[Any] = {
    if (codeActions.nonEmpty) {
      if (selectedActionIndex >= codeActions.length) {
        Assertions.fail(
          s"selectedActionIndex ($selectedActionIndex) is out of bounds"
        )
      }
      val codeAction = codeActions(selectedActionIndex)
      val edit = codeAction.getEdit()
      val command = codeAction.getCommand()
      if (edit != null) {
        applyWorkspaceEdit(edit)
      }
      if (command != null) {
        server.executeCommandUnsafe(
          command.getCommand(),
          command.getArguments().asScala.toSeq,
        )
      } else Future.unit

    } else Future.unit
  }

}
