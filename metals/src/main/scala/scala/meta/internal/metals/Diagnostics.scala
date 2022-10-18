package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.parsing.TokenEditDistance
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}

private final case class CompilationStatus(
    code: bsp4j.StatusCode,
    errors: Int,
)

/**
 * Converts diagnostics from the build server and Scalameta parser into LSP diagnostics.
 *
 * BSP diagnostics have different semantics from LSP diagnostics with regards to how they
 * are published. BSP diagnostics can be accumulated when `reset=false`, meaning the client
 * (Metals) is responsible for aggregating multiple `build/publishDiagnostics` notifications
 * into a single `textDocument/publishDiagnostics` notification.
 *
 * Another challenge is to consolidate syntax errors on every keystroke with type errors
 * on batch compile because positions get stale in batch compile errors as you type new
 * syntax errors. To solve this problem we use token edit distance the same way we support
 * goto definition in stale buffers.
 */
final class Diagnostics(
    buffers: Buffers,
    languageClient: LanguageClient,
    statistics: StatisticsConfig,
    workspace: Option[AbsolutePath],
    trees: Trees,
) {
  private val diagnostics =
    TrieMap.empty[AbsolutePath, ju.Queue[Diagnostic]]
  private val syntaxError =
    TrieMap.empty[AbsolutePath, Diagnostic]
  private val snapshots =
    TrieMap.empty[AbsolutePath, Input.VirtualFile]
  private val lastPublished =
    new AtomicReference[AbsolutePath]()
  private val diagnosticsBuffer =
    new ConcurrentLinkedQueue[AbsolutePath]()
  private val compileTimer =
    TrieMap.empty[BuildTargetIdentifier, Timer]
  private val compilationStatus =
    TrieMap.empty[BuildTargetIdentifier, CompilationStatus]

  def reset(): Unit = {
    val keys = diagnostics.keys
    diagnostics.clear()
    keys.foreach { key => publishDiagnostics(key) }
  }

  def reset(paths: Seq[AbsolutePath]): Unit =
    for (path <- paths if diagnostics.contains(path)) {
      diagnostics.remove(path)
      publishDiagnostics(path)
    }

  def resetAmmoniteScripts(): Unit =
    for (key <- diagnostics.keys if key.isAmmoniteScript) {
      diagnostics.remove(key)
      publishDiagnostics(key)
    }

  def onStartCompileBuildTarget(target: BuildTargetIdentifier): Unit = {
    if (statistics.isDiagnostics) {
      compileTimer(target) = new Timer(Time.system)
    }
  }

  def onFinishCompileBuildTarget(
      report: bsp4j.CompileReport,
      statusCode: bsp4j.StatusCode,
  ): Unit = {
    publishDiagnosticsBuffer()

    val target = report.getTarget()
    compileTimer.remove(target)

    val status = CompilationStatus(statusCode, report.getErrors())
    compilationStatus.update(target, status)
  }

  def onSyntaxError(path: AbsolutePath, diags: List[Diagnostic]): Unit = {
    diags.headOption match {
      case Some(diagnostic) if !workspace.exists(path.isInReadonlyDirectory) =>
        syntaxError(path) = diagnostic
        publishDiagnostics(path)
      case _ =>
        onClose(path)
    }
  }

  def onClose(path: AbsolutePath): Unit = {
    val diags = if (path.isWorksheet) {
      diagnostics.remove(path).toList.flatMap(_.asScala) ++
        syntaxError.remove(path)
    } else syntaxError.remove(path).toList
    diags match {
      case Nil =>
        () // Do nothing, there was no previous error.
      case _ =>
        publishDiagnostics(path) // Remove old syntax error.
    }
  }

  def didDelete(path: AbsolutePath): Unit = {
    diagnostics.remove(path)
    syntaxError.remove(path)
    languageClient.publishDiagnostics(
      new PublishDiagnosticsParams(
        path.toURI.toString(),
        ju.Collections.emptyList(),
      )
    )
  }

  def didChange(path: AbsolutePath): Unit = {
    publishDiagnostics(path)
  }

  def onBuildPublishDiagnostics(
      params: bsp4j.PublishDiagnosticsParams
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    onPublishDiagnostics(
      path,
      params.getDiagnostics().asScala.map(_.toLsp).toSeq,
      params.getReset(),
    )
  }

  def onPublishDiagnostics(
      path: AbsolutePath,
      diagnostics: Seq[Diagnostic],
      isReset: Boolean,
  ): Unit = {
    val isSamePathAsLastDiagnostic = path == lastPublished.get()
    lastPublished.set(path)
    val queue = this.diagnostics.getOrElseUpdate(
      path,
      new ConcurrentLinkedQueue[Diagnostic](),
    )
    if (isReset) {
      queue.clear()
      snapshots.remove(path)
    }
    if (queue.isEmpty && !diagnostics.isEmpty) {
      snapshots(path) = path.toInput
    }
    diagnostics.foreach { diagnostic => queue.add(diagnostic) }

    // NOTE(olafur): we buffer up several diagnostics for the same path before forwarding
    // them to the editor client. Without buffering, we risk publishing an exponential number
    // notifications for a file with N number of diagnostics:
    // Notification 1: [1]
    // Notification 2: [1, 2]
    // Notification 3: [1, 2, 3]
    // Notification N: [1, ..., N]
    if (isReset || !isSamePathAsLastDiagnostic) {
      publishDiagnosticsBuffer()
      publishDiagnostics(path, queue)
    } else {
      diagnosticsBuffer.add(path)
    }
  }

  private def publishDiagnostics(path: AbsolutePath): Unit = {
    publishDiagnostics(
      path,
      diagnostics.getOrElse(path, new ju.LinkedList[Diagnostic]()),
    )
  }

  def hasCompilationErrors(buildTarget: BuildTargetIdentifier): Boolean = {
    compilationStatus
      .get(buildTarget)
      .map(status => status.code.isError || status.errors > 0)
      .getOrElse(false)
  }

  def hasSyntaxError(path: AbsolutePath): Boolean =
    syntaxError.contains(path)

  def hasDiagnosticError(path: AbsolutePath): Boolean = {
    val fileDiagnostics = diagnostics
      .get(path)

    fileDiagnostics match {
      case Some(diagnostics) =>
        diagnostics.asScala.exists(
          _.getSeverity() == l.DiagnosticSeverity.Error
        )
      case None => false
    }
  }

  private def publishDiagnostics(
      path: AbsolutePath,
      queue: ju.Queue[Diagnostic],
  ): Unit = {
    if (!path.isFile) return didDelete(path)
    val current = path.toInputFromBuffers(buffers)
    val snapshot = snapshots.getOrElse(path, current)
    val edit = TokenEditDistance(
      snapshot,
      current,
      trees,
      doNothingWhenUnchanged = false,
    )
    val uri = path.toURI.toString
    val all = new ju.ArrayList[Diagnostic](queue.size() + 1)
    for {
      diagnostic <- queue.asScala
      freshDiagnostic <- toFreshDiagnostic(edit, diagnostic, snapshot)
    } {
      all.add(freshDiagnostic)
    }
    for {
      d <- syntaxError.get(path)
      // De-duplicate only the most common and basic syntax errors.
      isDuplicate =
        d.getMessage.startsWith("identifier expected but") &&
          all.asScala.exists { other =>
            other.getMessage.startsWith("identifier expected") &&
            other.getRange == d.getRange
          }
      if !isDuplicate
    } {
      all.add(d)
    }
    languageClient.publishDiagnostics(new PublishDiagnosticsParams(uri, all))
  }

  private def publishDiagnosticsBuffer(): Unit = {
    clearDiagnosticsBuffer().foreach { path => publishDiagnostics(path) }
  }

  // Adjust positions for type errors for changes in the open buffer.
  // Only needed when merging syntax errors with type errors.
  private def toFreshDiagnostic(
      edit: TokenEditDistance,
      d: Diagnostic,
      snapshot: Input,
  ): Option[Diagnostic] = {
    val result = edit.toRevised(d.getRange).map { range =>
      val ld = new l.Diagnostic(
        range,
        d.getMessage,
        d.getSeverity,
        d.getSource,
      )
      ld.setData(d.getData)
      ld
    }
    if (result.isEmpty) {
      d.getRange.toMeta(snapshot).foreach { pos =>
        val message = pos.formatMessage(
          s"stale ${d.getSource} ${d.getSeverity.toString.toLowerCase()}",
          d.getMessage,
        )
        scribe.info(message)
      }
    }
    result
  }

  private def clearDiagnosticsBuffer(): Iterable[AbsolutePath] = {
    val toPublish = mutable.Set.empty[AbsolutePath]
    var path = diagnosticsBuffer.poll()
    while (path != null) {
      toPublish.add(path)
      path = diagnosticsBuffer.poll()
    }
    toPublish
  }

}
