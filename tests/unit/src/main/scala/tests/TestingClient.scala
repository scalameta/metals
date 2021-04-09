package tests

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

import scala.meta.inputs.Input
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.decorations.DecorationOptions
import scala.meta.internal.decorations.PublishDecorationsParams
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.MetalsStatusParams
import scala.meta.internal.metals.NoopLanguageClient
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.tvp.TreeViewDidChangeParams
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
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
final class TestingClient(workspace: AbsolutePath, buffers: Buffers)
    extends NoopLanguageClient {
  val diagnostics: TrieMap[AbsolutePath, Seq[Diagnostic]] =
    TrieMap.empty[AbsolutePath, Seq[Diagnostic]]
  val diagnosticsCount: TrieMap[AbsolutePath, AtomicInteger] =
    TrieMap.empty[AbsolutePath, AtomicInteger]
  val messageRequests = new ConcurrentLinkedDeque[String]()
  val showMessages = new ConcurrentLinkedQueue[MessageParams]()
  val statusParams = new ConcurrentLinkedQueue[MetalsStatusParams]()
  val logMessages = new ConcurrentLinkedQueue[MessageParams]()
  val treeViewChanges = new ConcurrentLinkedQueue[TreeViewDidChangeParams]()
  val clientCommands = new ConcurrentLinkedDeque[ExecuteCommandParams]()
  val decorations =
    new ConcurrentHashMap[AbsolutePath, Array[DecorationOptions]]()
  var slowTaskHandler: MetalsSlowTaskParams => Option[MetalsSlowTaskResult] = {
    _: MetalsSlowTaskParams => None
  }
  var showMessageRequestHandler
      : ShowMessageRequestParams => Option[MessageActionItem] = {
    _: ShowMessageRequestParams => None
  }
  var inputBoxHandler: MetalsInputBoxParams => Option[MetalsInputBoxResult] = {
    _: MetalsInputBoxParams => None
  }

  private val refreshCount = new AtomicInteger
  var refreshModelHandler: Int => Unit = (_) => ()

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    clientCommands.addLast(params)
    params.getCommand match {
      case ClientCommands.RefreshModel.id =>
        refreshModelHandler(refreshCount.getAndIncrement())
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
        ResourceOperations.applyResourceOperation(resourceOperation)
    }
  }

  private def applyEdits(
      uri: String,
      textEdits: java.util.List[TextEdit]
  ): Unit = {
    val path = AbsolutePath.fromAbsoluteUri(URI.create(uri))

    val content = path.readText
    val editedContent =
      TextEdits.applyEdits(content, textEdits.asScala.toList)

    path.writeText(editedContent)
  }

  def workspaceClientCommands: List[String] = {
    clientCommands.asScala.toList.map(_.getCommand)
  }

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
    TestingServer.toPath(workspace, filename)
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
    diagnostics(path) = params.getDiagnostics.asScala
    diagnosticsCount
      .getOrElseUpdate(path, new AtomicInteger())
      .incrementAndGet()
  }
  override def showMessage(params: MessageParams): Unit = {
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
    // NOTE: (ckipp01) Just for easiness of testing, we are going to just look
    // for sbt and mill builds together, which are most common. The logic however
    // is identical for all build tools.
    def isSameMessageFromList(
        createParams: List[BuildTool] => ShowMessageRequestParams
    ): Boolean = {
      val buildTools = BuildTools
        .default()
        .allAvailable
        .filter(bt => bt.executableName == "sbt" || bt.executableName == "mill")

      val targetParams = createParams(buildTools)
      params == targetParams
    }

    CompletableFuture.completedFuture {
      messageRequests.addLast(params.getMessage)
      showMessageRequestHandler(params).getOrElse {
        if (isSameMessage(ImportBuildChanges.params)) {
          ImportBuildChanges.yes
        } else if (isSameMessage(ImportBuild.params)) {
          ImportBuild.yes
        } else if (BloopVersionChange.params() == params) {
          BloopVersionChange.notNow
        } else if (CheckDoctor.isDoctor(params)) {
          CheckDoctor.moreInformation
        } else if (SelectBspServer.isSelectBspServer(params)) {
          params.getActions.asScala.find(_.getTitle == "Bob").get
        } else if (isSameMessageFromList(ChooseBuildTool.params)) {
          params.getActions.asScala.toList
            .find(_.getTitle == "sbt")
            .getOrElse(new MessageActionItem("fail"))
        } else if (MissingScalafmtConf.isCreateScalafmtConf(params)) {
          null
        } else if (params.getMessage() == MainClass.message) {
          params.getActions.asScala.find(_.getTitle == "a.Main").get
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

  override def metalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[MetalsInputBoxResult] = {
    CompletableFuture.completedFuture {
      messageRequests.addLast(params.prompt)
      inputBoxHandler(params) match {
        case Some(result) => result
        case None => MetalsInputBoxResult(cancelled = true)
      }
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
    decorations.put(path, params.options)
  }

  def workspaceDecorations: String = {
    workspaceDecorations(isHover = false)
  }
  def workspaceDecorationHoverMessage: String = {
    workspaceDecorations(isHover = true)
  }
  private def workspaceDecorations(isHover: Boolean): String = {
    val out = new StringBuilder()
    val nonEmptyDecorations = decorations.asScala.filter {
      case (_, decorations) => decorations.nonEmpty
    }
    val isSingle = nonEmptyDecorations.size == 1
    nonEmptyDecorations.foreach { case (path, decorations) =>
      if (!isSingle) {
        out
          .append("/")
          .append(path.toRelative(workspace).toURI(false).toString())
          .append("\n")
      }
      val input = path.toInputFromBuffers(buffers)
      input.text.linesIterator.zipWithIndex.foreach { case (line, i) =>
        val lineDecorations = decorations
          .filter(_.range.getEnd().getLine() == i)
          .sortBy(_.range.getEnd().getCharacter())
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

  def workspaceTreeViewChanges: String = {
    treeViewChanges.asScala.toSeq
      .map { change =>
        change.nodes
          .map(n => n.viewId + " " + Option(n.nodeUri).getOrElse("<root>"))
          .mkString("\n")
      }
      .sorted
      .mkString("----\n")
  }

  private def executeServerCommand(
      command: Command,
      server: TestingServer
  ): Future[Any] = {
    server.executeCommand(
      command.getCommand(),
      command.getArguments().asScala: _*
    )
  }

  def applyCodeAction(
      selectedActionIndex: Int,
      codeActions: List[CodeAction],
      server: TestingServer
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
        executeServerCommand(command, server)
      } else Future.unit

    } else Future.unit
  }

}
