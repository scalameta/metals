package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

import scala.meta.inputs.Input
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PositionSyntax._
import scala.meta.internal.parsing.TokenEditDistance
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

case class DiagnosticWithOrigin(diagnostic: Diagnostic, originId: String)

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
    scalaVersionSelector: ScalaVersionSelector,
    buildTargets: BuildTargets,
    downstreamTargets: PreviouslyCompiledDownsteamTargets,
    config: MetalsServerConfig,
    clientConfig: ClientConfiguration,
) {
  private val diagnostics =
    TrieMap.empty[AbsolutePath, ju.Queue[DiagnosticWithOrigin]]
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

  def forFile(path: AbsolutePath): Seq[Diagnostic] = {
    diagnostics
      .getOrElse(path, new ConcurrentLinkedQueue[DiagnosticWithOrigin]())
      .asScala
      .map(_.diagnostic)
      .toList
  }

  def allDiagnostics: Seq[(AbsolutePath, Diagnostic)] =
    diagnostics.toList.flatMap { case (path, queue) =>
      queue.asScala.map(diag => (path, diag.diagnostic))
    }

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

  def onStartCompileBuildTarget(target: BuildTargetIdentifier): Unit = {
    if (statistics.isDiagnostics) {
      compileTimer(target) = new Timer(Time.system)
    }
  }

  def onFinishCompileBuildTarget(
      report: bsp4j.CompileReport,
      statusCode: bsp4j.StatusCode,
      originId: String,
  ): Unit = {
    val target = report.getTarget()

    // if we use best effort compilation downstream targets
    // should get recompiled even if compilation fails
    def shouldUnpublishForDownstreamTargets =
      !(buildTargets
        .scalaTarget(target)
        .exists(_.isBestEffort) && config.enableBestEffort)
    if (statusCode.isError && shouldUnpublishForDownstreamTargets) {
      removeInverseDependenciesDiagnostics(target)
    } else {
      downstreamTargets.remove(target)
    }

    // Bazel doesn't clean diagnostics for paths with no errors, so instead we remove everything
    // from previous compilations.
    val isBazel = buildTargets.buildServerOf(target).exists(_.isBazel)
    if (isBazel) {
      diagnostics
        .filter { case (path, _) =>
          buildTargets.inverseSources(path).exists(target => target == target)
        }
        .foreach { case (path, queue) =>
          val updatedQueue = queue.asScala.filter {
            case DiagnosticWithOrigin(_, diagOriginId) =>
              diagOriginId == originId
          }
          if (updatedQueue.isEmpty) {
            reset(Seq(path))
          }
          diagnostics.remove(path)

        }
    }
    publishDiagnosticsBuffer()

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
    val diagnostics =
      for {
        path <- Try(params.getTextDocument.getUri.toAbsolutePath).toOption
        shouldShowExplainDiagnostic = buildTargets
          .inverseSources(path)
          .flatMap(buildTargets.scalaTarget)
          .exists(target => target.supportExplainDiagnostic)
        isVirtualDocumentSupported = clientConfig
          .isVirtualDocumentSupported() && shouldShowExplainDiagnostic
        diagnostics = params
          .getDiagnostics()
          .asScala
          .map(
            _.toLsp(
              path,
              isVirtualDocumentSupported,
            )
          )
          .toSeq
        if (path.isFile)
        _ = onPublishDiagnostics(
          path,
          diagnostics,
          params.getReset(),
          params.getOriginId(),
        )
      } yield diagnostics

    diagnostics.getOrElse {
      scribe.warn(
        s"Invalid text document uri received from build server: ${params.getTextDocument.getUri}"
      )
    }
  }

  def onPublishDiagnostics(
      path: AbsolutePath,
      diagnostics: Seq[Diagnostic],
      isReset: Boolean,
      originId: String,
  ): Unit = {
    val isSamePathAsLastDiagnostic = path == lastPublished.get()
    lastPublished.set(path)
    val queue = this.diagnostics.getOrElseUpdate(
      path,
      new ConcurrentLinkedQueue[DiagnosticWithOrigin](),
    )
    if (isReset) {
      queue.clear()
      snapshots.remove(path)
    }
    if (queue.isEmpty && !diagnostics.isEmpty) {
      snapshots(path) = path.toInput
    }
    diagnostics.foreach { diagnostic =>
      queue.add(DiagnosticWithOrigin(diagnostic, originId))
    }

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

  private def removeInverseDependenciesDiagnostics(
      buildTarget: BuildTargetIdentifier
  ) = {
    val inverseDeps =
      buildTargets.allInverseDependencies(buildTarget) - buildTarget

    val targets = for {
      path <- diagnostics.keySet
      targets <- buildTargets.sourceBuildTargets(path)
      targetSet = targets.toSet
      /* We add the check for `!targetSet.contains(buildTarget)` to avoid removing diagnostics
       * if a file is duplicated across targets */
      if targets.exists(inverseDeps.apply) && !targetSet.contains(buildTarget)
    } yield {
      diagnostics.remove(path)
      publishDiagnostics(path)
      targets
    }

    val targetsSet = targets.flatten.toSet
    if (targetsSet.nonEmpty) {
      downstreamTargets.addMapping(buildTarget, targetsSet)
    }
  }

  private def publishDiagnostics(path: AbsolutePath): Unit = {
    publishDiagnostics(
      path,
      diagnostics.getOrElse(path, new ju.LinkedList[DiagnosticWithOrigin]()),
    )
  }

  def hasCompilationErrors(buildTarget: BuildTargetIdentifier): Boolean = {
    compilationStatus
      .get(buildTarget)
      .map(status => status.code.isError || status.errors > 0)
      .getOrElse(false)
  }

  def upstreamTargetsWithCompilationErrors(
      buildTarget: BuildTargetIdentifier
  ): List[BuildTargetIdentifier] = {
    buildTargets
      .buildTargetTransitiveDependencies(buildTarget)
      .filter(id => id != buildTarget && hasCompilationErrors(id))
      .toList
  }

  def hasSyntaxError(path: AbsolutePath): Boolean =
    syntaxError.contains(path)

  def hasDiagnosticError(path: AbsolutePath): Boolean = {
    val fileDiagnostics = diagnostics
      .get(path)

    fileDiagnostics match {
      case Some(diagnostics) =>
        diagnostics.asScala.exists(
          _.diagnostic.getSeverity() == l.DiagnosticSeverity.Error
        )
      case None => false
    }
  }

  def getFileDiagnostics(path: AbsolutePath): List[Diagnostic] =
    diagnostics.get(path).map(_.asScala.map(_.diagnostic).toList).getOrElse(Nil)

  private def publishDiagnostics(
      path: AbsolutePath,
      queue: ju.Queue[DiagnosticWithOrigin],
  ): Unit = {
    if (!path.isFile) return didDelete(path)
    val uri = path.toURI.toString
    val all = new ju.ArrayList[Diagnostic](queue.size() + 1)
    for {
      diagnostic <- queue.asScala
      freshDiagnostic <- toFreshDiagnostic(path, diagnostic.diagnostic)
    } {
      all.add(freshDiagnostic)
    }
    for {
      d <- syntaxError.get(path)
      // De-duplicate only the most common and basic syntax errors.
      isSameMessage = all.asScala.exists(diag =>
        diag.getRange() == d.getRange() && diag.getMessage() == d.getMessage()
      )
      isDuplicate =
        d.getMessage.replace("`", "").startsWith("identifier expected but") &&
          all.asScala.exists { other =>
            other.getMessage
              .replace("`", "")
              .startsWith("identifier expected") &&
            other.getRange().getStart() == d.getRange().getStart()
          }
      if !isDuplicate && !isSameMessage
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
  def toFreshDiagnostic(
      path: AbsolutePath,
      d: Diagnostic,
      fallbackToNearest: Boolean = true,
  ): Option[Diagnostic] = {
    val snapshot = snapshots.get(path)
    snapshot match {
      case Some(snapshot) =>
        val edit =
          buffers.tokenEditDistance(path, snapshot.value, scalaVersionSelector)
        val result = edit
          .toRevised(
            range = d.getRange,
            adjustWithinToken = shouldAdjustWithinToken(d),
            fallbackToNearest = fallbackToNearest,
          )
          .map { range =>
            val ld = new l.Diagnostic(
              range,
              d.getMessage,
              d.getSeverity,
              d.getSource,
            )
            // Scala 3 sets the diagnostic code to -1 for NoExplanation Messages. Ideally
            // this will change and we won't need this check in the future, but for now
            // let's not forward them, since they are not valid for all clients.
            val isScala3NoExplanationDiag = d.getCode() != null && d
              .getCode()
              .isLeft() && d.getCode().getLeft() == "-1"
            if (!isScala3NoExplanationDiag) ld.setCode(d.getCode())

            ld.setTags(d.getTags())
            ld.setRelatedInformation(d.getRelatedInformation)
            ld.setCodeDescription(d.getCodeDescription())
            adjustedDiagnosticData(d, edit)
              .map(newData => ld.setData(newData))
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

      case None =>
        Some(d)
    }
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

  private def adjustedDiagnosticData(
      diagnostic: l.Diagnostic,
      edit: TokenEditDistance,
  ): Option[Object] =
    diagnostic match {
      case ScalacDiagnostic.ScalaDiagnostic(Left(textEdit)) =>
        edit
          .toRevised(
            textEdit,
            shouldAdjustWithinToken(diagnostic),
            fallbackToNearest = false,
          )
          .map(_.toJsonObject)
      case ScalacDiagnostic.ScalaDiagnostic(Right(scalaDiagnostic)) =>
        edit
          .toRevised(
            scalaDiagnostic,
            shouldAdjustWithinToken(diagnostic),
            fallbackToNearest = false,
          )
          .map(_.toJsonObject)
      case _ => Some(diagnostic.getData())
    }

  private def shouldAdjustWithinToken(diagnostic: l.Diagnostic): Boolean =
    diagnostic.getSource() == "scala-cli"
}
