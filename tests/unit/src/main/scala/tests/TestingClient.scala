package tests

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import scala.collection.concurrent.TrieMap
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.MetalsStatusParams
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.io.AbsolutePath

/**
 * Fake LSP client that responds to notifications/requests initiated by the server.
 *
 * - Can customize how to respond to window/showMessageRequest
 * - Aggregates published diagnostics and pretty-prints them as strings
 */
final class TestingClient(workspace: AbsolutePath, buffers: Buffers)
    extends MetalsLanguageClient {
  val diagnostics = TrieMap.empty[AbsolutePath, Seq[Diagnostic]]
  val diagnosticsCount = TrieMap.empty[AbsolutePath, AtomicInteger]
  val messageRequests = new ConcurrentLinkedQueue[String]()
  val showMessages = new ConcurrentLinkedQueue[MessageParams]()
  val statusParams = new ConcurrentLinkedQueue[MetalsStatusParams]()
  val logMessages = new ConcurrentLinkedQueue[MessageParams]()
  var slowTaskHandler: MetalsSlowTaskParams => Option[MetalsSlowTaskResult] = {
    _: MetalsSlowTaskParams =>
      None
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
    val diags = diagnostics.getOrElse(path, Nil)
    val relpath =
      path.toRelative(workspace).toURI(isDirectory = false).toString
    val input =
      path.toInputFromBuffers(buffers).copy(path = relpath)
    val sb = new StringBuilder
    diags.foreach { diag =>
      val start = diag.getRange.getStart
      val end = diag.getRange.getEnd
      val pos = Position.Range(
        input,
        start.getLine,
        start.getCharacter,
        end.getLine,
        end.getCharacter
      )
      val message = pos.formatMessage(
        diag.getSeverity.toString.toLowerCase(),
        diag.getMessage
      )
      sb.append(message).append("\n")
    }
    sb.toString()
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
  ): CompletableFuture[MessageActionItem] =
    CompletableFuture.completedFuture {
      messageRequests.add(params.getMessage)
      if (params == ReimportSbtProject.params) {
        ReimportSbtProject.yes
      } else if (params == ImportBuildViaBloop.params) {
        ImportBuildViaBloop.yes
      } else if (params == Only212Navigation.params("2.11.12")) {
        Only212Navigation.dismissForever
      } else {
        throw new IllegalArgumentException(params.toString)
      }
    }
  override def logMessage(params: MessageParams): Unit = {
    logMessages.add(params)
  }
  override def metalsSlowTask(
      params: MetalsSlowTaskParams
  ): CompletableFuture[MetalsSlowTaskResult] = {
    CompletableFuture.completedFuture {
      messageRequests.add(params.message)
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
}
