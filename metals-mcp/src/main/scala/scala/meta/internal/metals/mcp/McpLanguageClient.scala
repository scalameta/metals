package scala.meta.internal.metals.mcp

import java.util.concurrent.CompletableFuture
import java.{util => ju}

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TextEdits
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.RawMetalsInputBoxResult
import scala.meta.internal.metals.clients.language.RawMetalsQuickPickResult
import scala.meta.internal.tvp.TreeViewDidChangeParams
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowDocumentResult
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder

/**
 * An implementation of MetalsLanguageClient for standalone MCP mode.
 *
 * This client is used when running Metals as a standalone MCP server without
 * an LSP client connection. It logs messages to scribe instead of sending them
 * to a client, and handles workspace edits by writing directly to files.
 *
 * @param workspace The workspace root path, used for resolving file paths
 */
class McpLanguageClient(workspace: AbsolutePath) extends MetalsLanguageClient {

  override def telemetryEvent(value: Object): Unit = {
    scribe.debug(s"[MCP Telemetry] $value")
  }

  override def publishDiagnostics(
      diagnostics: PublishDiagnosticsParams
  ): Unit = {
    // diagnotics are server via the compile request
  }

  override def showMessage(message: MessageParams): Unit = {
    val level = message.getType match {
      case MessageType.Error => "ERROR"
      case MessageType.Warning => "WARN"
      case MessageType.Info => "INFO"
      case MessageType.Log => "DEBUG"
      case _ => "INFO"
    }
    scribe.info(s"[MCP $level] ${message.getMessage}")
  }

  override def showMessageRequest(
      request: ShowMessageRequestParams
  ): CompletableFuture[MessageActionItem] = {

    if (request.getMessage.contains("workspace detected")) {
      CompletableFuture.completedFuture(Messages.ImportBuild.yes)
    } else {
      scribe.error(s"[MCP Request not handled] ${request.getMessage}")
      // Return null to indicate no selection (as there's no user to interact with)
      CompletableFuture.completedFuture(null)
    }
  }

  override def logMessage(message: MessageParams): Unit = {
    message.getType match {
      case MessageType.Error => scribe.error(s"[MCP Log] ${message.getMessage}")
      case MessageType.Warning =>
        scribe.warn(s"[MCP Log] ${message.getMessage}")
      case MessageType.Info => scribe.info(s"[MCP Log] ${message.getMessage}")
      case _ => scribe.debug(s"[MCP Log] ${message.getMessage}")
    }
  }

  /**
   * Apply workspace edits by writing directly to files.
   *
   * In standalone MCP mode, we don't have an LSP client to apply edits,
   * so we write changes directly to the filesystem.
   */
  override def applyEdit(
      params: ApplyWorkspaceEditParams
  ): CompletableFuture[ApplyWorkspaceEditResponse] = {
    val edit = params.getEdit
    val response = new ApplyWorkspaceEditResponse(true)

    def updateFile(path: AbsolutePath, textEdits: ju.List[TextEdit]): Unit = {
      val content = path.readText
      val currentContent =
        TextEdits.applyEdits(content, textEdits.asScala.toList)
      path.writeText(currentContent)

      scribe.debug(
        s"[MCP Edit] Applied ${textEdits.size} versioned edit(s) to $path"
      )
    }

    try {
      // Handle document changes
      val changes = Option(edit.getChanges)
      changes.foreach { changesMap =>
        changesMap.forEach { (uri, textEdits) =>
          val path = uri.toAbsolutePath
          if (path.exists) {
            updateFile(path, textEdits)
          }
        }
      }

      // Handle document changes (versioned)
      val documentChanges = Option(edit.getDocumentChanges)
      documentChanges.foreach { changes =>
        changes.forEach { change =>
          if (change.isLeft) {
            val textDocEdit = change.getLeft
            val uri = textDocEdit.getTextDocument.getUri
            val path = uri.toAbsolutePath

            if (path.exists) {
              val textEdits = textDocEdit.getEdits.asScala
                .flatMap(e => if (e.isLeft) Some(e.getLeft) else None)
                .toList
              updateFile(path, textEdits.asJava)
            }
          }
        }
      }

    } catch {
      case e: Exception =>
        scribe.error(s"[MCP Edit] Failed to apply edits: ${e.getMessage}", e)
        response.setApplied(false)
        response.setFailureReason(e.getMessage)
    }

    CompletableFuture.completedFuture(response)
  }

  override def metalsTreeViewDidChange(
      params: TreeViewDidChangeParams
  ): Unit = {
    scribe.debug(s"[MCP TreeView] Changed: ${params.nodes.length} nodes")
  }

  // Status bar - log to scribe
  override def metalsStatus(params: MetalsStatusParams): Unit = {
    if (params.text != null && params.text.nonEmpty) {
      scribe.info(s"[MCP Status] ${params.text}")
    }
  }

  // Execute client command - no-op,
  override def metalsExecuteClientCommand(
      params: ExecuteCommandParams
  ): Unit = {
    scribe.debug(s"[MCP Command] ${params.getCommand}")
  }

  // Refresh model - no-op
  override def refreshModel(): CompletableFuture[Unit] = {
    CompletableFuture.completedFuture(())
  }

  // Input box - return cancelled (no user interaction)
  override def rawMetalsInputBox(
      params: MetalsInputBoxParams
  ): CompletableFuture[RawMetalsInputBoxResult] = {
    scribe.debug(s"[MCP InputBox] ${params.prompt}")
    CompletableFuture.completedFuture(RawMetalsInputBoxResult(cancelled = true))
  }

  // Quick pick - return cancelled (no user interaction)
  override def rawMetalsQuickPick(
      params: MetalsQuickPickParams
  ): CompletableFuture[RawMetalsQuickPickResult] = {
    scribe.debug(s"[MCP QuickPick] ${params.placeHolder}")
    CompletableFuture.completedFuture(
      RawMetalsQuickPickResult(cancelled = true)
    )
  }

  // Progress - log to scribe
  override def createProgress(
      params: WorkDoneProgressCreateParams
  ): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }

  override def notifyProgress(params: ProgressParams): Unit = {
    // Progress notifications are logged at debug level
    scribe.debug(s"[MCP Progress] ${params.getToken}")
  }

  // Registration - no-op
  override def registerCapability(
      params: RegistrationParams
  ): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }

  override def unregisterCapability(
      params: UnregistrationParams
  ): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }

  // Configuration - return empty
  override def configuration(
      params: ConfigurationParams
  ): CompletableFuture[java.util.List[Object]] = {
    CompletableFuture.completedFuture(java.util.Collections.emptyList())
  }

  // Workspace folders - return workspace
  override def workspaceFolders()
      : CompletableFuture[java.util.List[WorkspaceFolder]] = {
    val folder =
      new WorkspaceFolder(workspace.toURI.toString, workspace.filename)
    CompletableFuture.completedFuture(
      java.util.Collections.singletonList(folder)
    )
  }

  // Show document - no-op
  override def showDocument(
      params: ShowDocumentParams
  ): CompletableFuture[ShowDocumentResult] = {
    scribe.debug(s"[MCP ShowDocument] ${params.getUri}")
    CompletableFuture.completedFuture(new ShowDocumentResult(false))
  }

  // Semantic tokens refresh - no-op
  override def refreshSemanticTokens(): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }

  // Inlay hints refresh - no-op
  override def refreshInlayHints(): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }

  // Code lens refresh - no-op
  override def refreshCodeLenses(): CompletableFuture[Void] = {
    CompletableFuture.completedFuture(null)
  }
}
