package tests

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import scala.collection.concurrent.TrieMap
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.MetalsStatusParams
import scala.meta.io.AbsolutePath
import tests.MetalsTestEnrichments._
import tests.TestOrderings._
import scala.meta.inputs.Input
import scala.meta.internal.builds.GradleBuildTool
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.MavenBuildTool
import scala.meta.internal.builds.MillBuildTool
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.NoopLanguageClient
import scala.meta.internal.tvp.TreeViewDidChangeParams

/**
 * Fake LSP client that responds to notifications/requests initiated by the server.
 *
 * - Can customize how to respond to window/showMessageRequest
 * - Aggregates published diagnostics and pretty-prints them as strings
 */
final class TestingClient(workspace: AbsolutePath, buffers: Buffers)
    extends NoopLanguageClient {
  val diagnostics = TrieMap.empty[AbsolutePath, Seq[Diagnostic]]
  val diagnosticsCount = TrieMap.empty[AbsolutePath, AtomicInteger]
  val messageRequests = new ConcurrentLinkedDeque[String]()
  val showMessages = new ConcurrentLinkedQueue[MessageParams]()
  val statusParams = new ConcurrentLinkedQueue[MetalsStatusParams]()
  val logMessages = new ConcurrentLinkedQueue[MessageParams]()
  val treeViewChanges = new ConcurrentLinkedQueue[TreeViewDidChangeParams]()
  val clientCommands = new ConcurrentLinkedDeque[ExecuteCommandParams]()
  var slowTaskHandler: MetalsSlowTaskParams => Option[MetalsSlowTaskResult] = {
    _: MetalsSlowTaskParams =>
      None
  }
  var showMessageRequestHandler
      : ShowMessageRequestParams => Option[MessageActionItem] = {
    _: ShowMessageRequestParams =>
      None
  }

  private var refreshedOnIndex = false
  var refreshModelHandler: () => Unit = () => {}

  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    clientCommands.addLast(params)
    params.getCommand match {
      case ClientCommands.RefreshModel.id =>
        if (refreshedOnIndex) {
          refreshModelHandler()
        } else {
          refreshedOnIndex = true
        }
      case _ =>
    }
  }

  override def applyEdit(
      params: ApplyWorkspaceEditParams
  ): CompletableFuture[ApplyWorkspaceEditResponse] = {
    def applyEdits(uri: String, textEdits: java.util.List[TextEdit]): Unit = {
      val path = AbsolutePath(uri)

      val content = path.readText
      val editedContent =
        TextEdits.applyEdits(content, textEdits.asScala.toList)

      path.writeText(editedContent)
    }

    params.getEdit.getChanges.forEach(applyEdits)
    CompletableFuture.completedFuture(new ApplyWorkspaceEditResponse(true))
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
      .map { params =>
        s"${params.getType}: ${params.getMessage}"
      }
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
      Set(
        GradleBuildTool(),
        SbtBuildTool(""),
        MavenBuildTool(),
        MillBuildTool()
      ).map(tool => createParams(tool.toString()))
        .contains(params)
    }
    CompletableFuture.completedFuture {
      messageRequests.addLast(params.getMessage)
      showMessageRequestHandler(params).getOrElse {
        if (isSameMessage(ImportBuildChanges.params)) {
          ImportBuildChanges.yes
        } else if (isSameMessage(ImportBuild.params)) {
          ImportBuild.yes
        } else if (CheckDoctor.isDoctor(params)) {
          CheckDoctor.moreInformation
        } else if (SelectBspServer.isSelectBspServer(params)) {
          params.getActions.asScala.find(_.getTitle == "Bob").get
        } else if (MissingScalafmtConf.isCreateScalafmtConf(params)) {
          null
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
    CompletableFuture.completedFuture(MetalsInputBoxResult(cancelled = true))
  }

  override def metalsTreeViewDidChange(
      params: TreeViewDidChangeParams
  ): Unit = {
    treeViewChanges.add(params)
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

}

object TestingClient {
  trait Listener {
    def onRefreshModel(): Unit
  }
}
