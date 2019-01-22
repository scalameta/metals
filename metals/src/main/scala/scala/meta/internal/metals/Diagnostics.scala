package scala.meta.internal.metals

import ch.epfl.scala.bsp4j
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath
import scala.{meta => m}

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
    TrieMap.empty[AbsolutePath, util.Queue[Diagnostic]]
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
    syntaxError(path) = new Diagnostic(
      pos.toLSP,
      shortMessage,
      DiagnosticSeverity.Error,
      "scalameta"
    )
    publishDiagnostics(path)
  }

  def didChange(path: AbsolutePath): Unit = {
    publishDiagnostics(path)
  }

  def onBuildPublishDiagnostics(
      params: bsp4j.PublishDiagnosticsParams
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val isSamePathAsLastDiagnostic = path == lastPublished.get()
    lastPublished.set(path)
    val queue = diagnostics.getOrElseUpdate(
      path,
      new ConcurrentLinkedQueue[Diagnostic]()
    )
    if (params.getReset) {
      queue.clear()
      snapshots.remove(path)
    }
    if (queue.isEmpty && !params.getDiagnostics.isEmpty) {
      snapshots(path) = path.toInput
    }
    params.getDiagnostics.asScala.foreach { buildDiagnostic =>
      queue.add(buildDiagnostic.toLSP)
    }

    // NOTE(olafur): we buffer up several diagnostics for the same path before forwarding
    // them to the editor client. Without buffering, we risk publishing an exponential number
    // notifications for a file with N number of diagnostics:
    // Notification 1: [1]
    // Notification 2: [1, 2]
    // Notification 3: [1, 2, 3]
    // Notification N: [1, ..., N]
    if (params.getReset || !isSamePathAsLastDiagnostic) {
      publishDiagnosticsBuffer()
      publishDiagnostics(path, queue)
    } else {
      diagnosticsBuffer.add(path)
    }
  }

  private def publishDiagnostics(path: AbsolutePath): Unit = {
    publishDiagnostics(
      path,
      diagnostics.getOrElse(path, new util.LinkedList[Diagnostic]())
    )
  }

  def hasSyntaxError(path: AbsolutePath): Boolean =
    syntaxError.contains(path)

  private def publishDiagnostics(
      path: AbsolutePath,
      queue: util.Queue[Diagnostic]
  ): Unit = {
    val current = path.toInputFromBuffers(buffers)
    val snapshot = snapshots.getOrElse(path, current)
    val edit = TokenEditDistance(
      snapshot,
      current,
      doNothingWhenUnchanged = false
    )
    val uri = path.toURI.toString
    val all = new util.ArrayList[Diagnostic](queue.size() + 1)
    for {
      diagnostic <- queue.asScala
      freshDiagnostic <- toFreshDiagnostic(edit, uri, diagnostic, snapshot)
    } {
      all.add(freshDiagnostic)
    }
    for {
      d <- syntaxError.get(path)
      // De-duplicate only the most common and basic syntax errors.
      isDuplicate = d.getMessage.startsWith("identifier expected but") &&
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
    clearDiagnosticsBuffer().foreach { path =>
      publishDiagnostics(path)
    }
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
        d.getSource,
        d.getCode
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
