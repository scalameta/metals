package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.{meta => m}

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}

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
    buildTargets: BuildTargets,
    buffers: Buffers,
    languageClient: LanguageClient,
    statistics: StatisticsConfig,
    config: () => UserConfiguration
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

  def reset(): Unit = {
    val keys = diagnostics.keys
    diagnostics.clear()
    keys.foreach { key => publishDiagnostics(key) }
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

  def onFinishCompileBuildTarget(target: BuildTargetIdentifier): Unit = {
    publishDiagnosticsBuffer()
    compileTimer.remove(target)
  }

  def onNoSyntaxError(path: AbsolutePath): Unit = {
    syntaxError.remove(path) match {
      case Some(_) =>
        publishDiagnostics(path) // Remove old syntax error.
      case None =>
        () // Do nothing, there was no previous syntax error.
    }
  }

  def onSyntaxError(
      path: AbsolutePath,
      pos: m.Position,
      shortMessage: String
  ): Unit = {
    onSyntaxError(
      path,
      new Diagnostic(
        pos.toLSP,
        shortMessage,
        DiagnosticSeverity.Error,
        "scalameta"
      )
    )
  }

  def onSyntaxError(path: AbsolutePath, d: Diagnostic): Unit = {
    syntaxError(path) = d
    publishDiagnostics(path)
  }

  def didDelete(path: AbsolutePath): Unit = {
    diagnostics.remove(path)
    syntaxError.remove(path)
    languageClient.publishDiagnostics(
      new PublishDiagnosticsParams(
        path.toURI.toString(),
        ju.Collections.emptyList()
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
      params.getDiagnostics().asScala.map(_.toLSP),
      params.getReset()
    )
  }

  def onPublishDiagnostics(
      path: AbsolutePath,
      diagnostics: Seq[Diagnostic],
      isReset: Boolean
  ): Unit = {
    val isSamePathAsLastDiagnostic = path == lastPublished.get()
    lastPublished.set(path)
    val queue = this.diagnostics.getOrElseUpdate(
      path,
      new ConcurrentLinkedQueue[Diagnostic]()
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
      diagnostics.getOrElse(path, new ju.LinkedList[Diagnostic]())
    )
  }

  def hasSyntaxError(path: AbsolutePath): Boolean =
    syntaxError.contains(path)

  private def publishDiagnostics(
      path: AbsolutePath,
      queue: ju.Queue[Diagnostic]
  ): Unit = {
    if (!path.isFile) return didDelete(path)
    val current = path.toInputFromBuffers(buffers)
    val snapshot = snapshots.getOrElse(path, current)
    val edit = TokenEditDistance(
      snapshot,
      current,
      doNothingWhenUnchanged = false
    )
    val uri = path.toURI.toString
    val all = new ju.ArrayList[Diagnostic](queue.size() + 1)
    for {
      diagnostic <- queue.asScala
      freshDiagnostic <- toFreshDiagnostic(edit, uri, diagnostic, snapshot)
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
      uri: String,
      d: Diagnostic,
      snapshot: Input
  ): Option[Diagnostic] = {
    val result = edit.toRevised(d.getRange).map { range =>
      new l.Diagnostic(
        range,
        d.getMessage,
        d.getSeverity,
        d.getSource
      )
    }
    if (result.isEmpty) {
      val pos = d.getRange.toMeta(snapshot)
      val message = pos.formatMessage(
        s"stale ${d.getSource} ${d.getSeverity.toString.toLowerCase()}",
        d.getMessage
      )
      scribe.info(message)
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

  private def logStatistics(
      path: AbsolutePath,
      prefix: String,
      suffix: String
  ): Unit = {
    if (statistics.isDiagnostics) {
      for {
        target <- buildTargets.inverseSources(path)
        timer <- compileTimer.get(target)
      } {
        scribe.info(s"$prefix: $timer $suffix")
      }
    }
  }

}
