package scala.meta.internal.metals.clients.language

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import scala.meta.internal.builds.BspErrorHandler
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.ConcurrentHashSet
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ModuleStatus
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.io.AbsolutePath

import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.{lsp4j => l}

/**
 * Used to forward messages from the build server. Messages might
 * be mixed if the server is sending messages as well as output from
 * running. This hasn't been a problem yet, not perfect solution,
 * but seems to work ok.
 */
trait LogForwarder {
  def error(message: String): Unit = ()
  def warn(message: String): Unit = ()
  def info(message: String): Unit = ()
  def log(message: String): Unit = ()
}

/**
 * A build client that forwards notifications from the build server to the language client.
 *
 * Known limitations of the "Compiling" progress tracking, all affecting the
 * indicator only and never compile reports (scalameta/metals#3464): a
 * compilation started by another BSP client is bound by no Metals request, so
 * it is cleaned only when the connection closes; an origin-less request can
 * end an overlapping compilation of the same target early; and task ids are
 * compared across servers, so a reused id can refresh the wrong compilation.
 */
final class ForwardingMetalsBuildClient(
    languageClient: MetalsLanguageClient,
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    time: Time,
    didCompile: b.CompileReport => Unit,
    onBuildTargetDidCompile: b.BuildTargetIdentifier => Unit,
    onBuildTargetDidChangeFunc: b.DidChangeBuildTarget => Unit,
    bspErrorHandler: BspErrorHandler,
    workDoneProgress: WorkDoneProgress,
    moduleStatus: ModuleStatus,
    hasBuildSession: () => Boolean,
) extends MetalsBuildClient
    with Cancelable {

  private val forwarders =
    new AtomicReference(List.empty[LogForwarder])

  def registerLogForwarder(
      logForwarder: LogForwarder
  ): List[LogForwarder] = {
    forwarders.getAndUpdate(_.prepended(logForwarder))
  }
  private class Compilation(
      val timer: Timer,
      // Captured at start so cleanup does not depend on the mutable routing.
      // The owner identifies the connection wrapper, which survives launcher
      // generations; per-generation staleness is handled by the launcher's
      // dispatch gate.
      val owner: Option[BuildServerConnection],
      val originId: Option[String],
      taskId: Option[String],
      token: Future[WorkDoneProgress.Token],
      taskProgress: TaskProgress = TaskProgress.empty,
  ) {

    def progressPercentage = taskProgress.percentage

    // this task's id plus the ids of its observed descendants, so that a
    // grandchild task, which only lists its direct parents, still resolves
    // to this compilation
    val taskIds: java.util.Set[String] = ConcurrentHashSet.empty[String]
    taskId.foreach(taskIds.add)

    // liveness refresh and termination share this object's monitor, so the
    // idle sweep can never end a compilation that just showed activity and
    // an ended compilation stays ended
    private var lastActivityNanos: Long = 0
    private var ended = false

    /** Refreshes liveness without touching the reported percentage. */
    def touch(): Unit = synchronized {
      if (!ended) lastActivityNanos = timer.elapsedNanos
    }

    def end(): Unit = synchronized {
      if (!ended) {
        ended = true
        workDoneProgress.endProgress(token)
      }
    }

    /**
     * Ends this compilation's progress iff it has shown no activity for
     * longer than `maxIdleMillis`, atomically with respect to activity
     * refreshes and other terminations. Returns whether it ended.
     */
    def endIfIdle(maxIdleMillis: Long): Boolean = synchronized {
      val idleMillis =
        TimeUnit.NANOSECONDS.toMillis(timer.elapsedNanos - lastActivityNanos)
      if (!ended && idleMillis > maxIdleMillis) {
        ended = true
        workDoneProgress.endProgress(token)
        true
      } else false
    }

    def updateProgress(progress: Long, total: Long = 100): Unit =
      synchronized {
        if (!ended) {
          lastActivityNanos = timer.elapsedNanos
          val prev = taskProgress.percentage
          taskProgress.update(progress, total)
          if (prev != taskProgress.percentage) {
            workDoneProgress.notifyProgress(token, progressPercentage)
          }
        }
      }
  }

  private val compilations = TrieMap.empty[b.BuildTargetIdentifier, Compilation]

  /**
   * Tombstones of finished compile requests: a request that already ran its
   * terminal cleanup may still get a delayed `build/taskStart`, which no
   * later boundary would ever end (metals#3464). Entries expire by time, not
   * only by count, so a backed-up executor cannot outlive them; the size cap
   * is a memory backstop.
   */
  private class Tombstones(retention: FiniteDuration, maxSize: Int) {
    private val entries = new java.util.LinkedHashMap[String, Long]() {
      override def removeEldestEntry(
          eldest: java.util.Map.Entry[String, Long]
      ): Boolean = size > maxSize
    }

    def add(keys: IterableOnce[String]): Unit = synchronized {
      val deadline = time.currentMillis() + retention.toMillis
      keys.iterator.foreach(entries.put(_, deadline))
    }

    def contains(key: String): Boolean = synchronized {
      Option(entries.get(key)) match {
        case Some(deadline) if deadline > time.currentMillis() => true
        case Some(_) =>
          entries.remove(key)
          false
        case None => false
      }
    }
  }

  // origins are unique per request, so they can be remembered generously
  private val tombstonedOrigins =
    new Tombstones(FiniteDuration(5, TimeUnit.MINUTES), maxSize = 256)

  // Targets are not unique: a tombstone here also suppresses a legitimate new
  // compilation of the same target, so the window is only wide enough to
  // cover a delayed dispatch of the request that just finished.
  private val tombstonedTargets =
    new Tombstones(FiniteDuration(5, TimeUnit.SECONDS), maxSize = 256)
  private val hasReportedError = Collections.newSetFromMap(
    new ConcurrentHashMap[b.BuildTargetIdentifier, java.lang.Boolean]()
  )

  val updatedTreeViews: java.util.Set[b.BuildTargetIdentifier] =
    ConcurrentHashSet.empty[b.BuildTargetIdentifier]

  def buildHasErrors(buildTargetId: b.BuildTargetIdentifier): Boolean = {
    buildTargets
      .buildTargetTransitiveDependencies(buildTargetId)
      .exists(hasReportedError.contains(_))
  }

  def buildHasErrors(file: AbsolutePath): Boolean = {
    buildTargets
      .inverseSources(file)
      .toSeq
      .flatMap(buildTargets.buildTargetTransitiveDependencies)
      .exists(hasReportedError.contains(_))
  }

  override def buildHasErrors: Boolean = !hasReportedError.isEmpty()

  def reset(): Unit = {
    cancel()
    updatedTreeViews.clear()
  }

  override def cancel(): Unit =
    endCompilations("build client was cancelled")((_, _) => true)

  override def onConnectionClosed(server: BuildServerConnection): Unit =
    // the connection is gone, its compilations will never receive a
    // `build/taskFinish`, end their progress (see scalameta/metals#3464)
    endCompilations("build server connection was closed")((_, compilation) =>
      compilation.owner.contains(server)
    )

  /**
   * A well-behaved server sends `build/taskFinish` before responding to a
   * compile request, so this only ends progress the server dropped
   * (see scalameta/metals#3464). Correlation is by `originId`, falling back
   * to the requested targets and their transitive dependencies, since
   * triggered notifications may omit the `originId`.
   */
  override def onCompileRequestFinished(
      originId: String,
      targets: Seq[b.BuildTargetIdentifier],
  ): Unit = {
    val requestedTargets =
      targets.flatMap(buildTargets.buildTargetTransitiveDependencies).toSet
    // Tombstoned before the scan, so a `build/taskStart` dispatched after it
    // is rejected on insert instead of leaking. Origin-less starts are
    // matched by target, mirroring the fallback used by the scan below.
    tombstonedOrigins.add(List(originId))
    tombstonedTargets.add(requestedTargets.map(_.getUri()))
    endCompilations("compile request finished without `build/taskFinish`")(
      (target, compilation) =>
        compilation.originId.contains(originId) ||
          (compilation.originId.isEmpty && requestedTargets.contains(target))
    )
  }

  /**
   * Bounded-liveness cleanup for requests without a usable terminal boundary,
   * e.g. `buildTarget/run` while its process keeps running: ends a matching
   * compilation only once it showed no task activity for `maxIdle`
   * (see scalameta/metals#3464). Matches by `originId`, or origin-less
   * compilations of the requested targets and their transitive dependencies.
   * Residual: a compilation whose server emits no task notifications at all
   * loses its indicator after `maxIdle`; report processing is unaffected.
   */
  override def endIdleCompilations(
      originId: String,
      targets: Seq[b.BuildTargetIdentifier],
      maxIdle: FiniteDuration,
  ): Unit = {
    val requestedTargets =
      targets.flatMap(buildTargets.buildTargetTransitiveDependencies).toSet
    for {
      target <- compilations.keysIterator
      compilation <- compilations.get(target)
      correlated =
        compilation.originId.contains(originId) ||
          (compilation.originId.isEmpty && requestedTargets.contains(target))
      if correlated
      if compilation.endIfIdle(maxIdle.toMillis)
    } {
      scribe.debug(
        s"ending compile progress for ${target.getUri}: no task activity for ${maxIdle.toSeconds}s, assuming `build/taskFinish` was dropped"
      )
      compilations.remove(target, compilation)
    }
  }

  private def endCompilations(
      reason: String
  )(shouldEnd: (b.BuildTargetIdentifier, Compilation) => Boolean): Unit = {
    for {
      target <- compilations.keysIterator
      compilation <- compilations.get(target)
      if shouldEnd(target, compilation)
      // atomic remove-if-unchanged, so a token is never ended twice even if
      // `taskFinish`, cancellation and connection loss race each other
      if compilations.remove(target, compilation)
    } {
      scribe.debug(s"ending compile progress for ${target.getUri}: $reason")
      compilation.end()
    }
  }

  def onBuildShowMessage(params: b.ShowMessageParams): Unit = {
    Option(params.getTask()).foreach(touchTask)
    languageClient.showMessage(
      new l.MessageParams(
        l.MessageType.forValue(params.getType().getValue()),
        params.getMessage(),
      )
    )
  }

  def onBuildLogMessage(params: b.LogMessageParams): Unit = {
    // a task-bound log message counts as task activity for the idle sweep
    Option(params.getTask()).foreach(touchTask)
    // NOTE: BazelBsp adds coloring to the log message after `workspaceBuildTargets` request
    val noANSICodes = filterANSIColorCodes(params.getMessage).trim()
    if (noANSICodes.nonEmpty) {
      params.getType match {
        case b.MessageType.ERROR =>
          bspErrorHandler.onError(noANSICodes)
          forwarders.get().foreach(_.error(params.getMessage()))
        case b.MessageType.WARNING =>
          forwarders.get().foreach(_.warn(params.getMessage()))
          scribe.warn(noANSICodes)
        case b.MessageType.INFO =>
          forwarders.get().foreach(_.info(params.getMessage()))
          scribe.info(noANSICodes)
        case b.MessageType.LOG =>
          forwarders.get().foreach(_.log(params.getMessage()))
          scribe.info(noANSICodes)
        case _ =>
          scribe.info(noANSICodes)
      }
    }
  }

  /**
   * Refreshes liveness of the compilations matching the given task or any of
   * its parent tasks: BSP relates child work, e.g. a compile subtask or a
   * task-bound log message, to its compilation through `TaskId.parents`.
   */
  private def touchTask(taskId: b.TaskId): Unit = {
    val id = Option(taskId.getId())
    val ids = id.toSet ++
      Option(taskId.getParents())
        .map(_.asScala.toSet)
        .getOrElse(Set.empty[String])
    if (ids.nonEmpty) {
      compilations.values.foreach { compilation =>
        if (ids.exists(compilation.taskIds.contains)) {
          // record the descendant so its own children keep resolving here
          id.foreach(compilation.taskIds.add)
          compilation.touch()
        }
      }
    }
  }

  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = {
    diagnostics.onBuildPublishDiagnostics(params)
  }

  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = {
    onBuildTargetDidChangeFunc(params)
  }

  def onBuildTargetCompileReport(params: b.CompileReport): Unit = {}

  @JsonNotification("build/taskStart")
  def buildTaskStart(params: b.TaskStartParams): Unit = {
    // a child task starting under a compilation counts as its activity
    Option(params.getTaskId()).foreach(touchTask)
    params.getDataKind match {
      case b.TaskStartDataKind.COMPILE_TASK =>
        if (
          params.getMessage != null && params.getMessage.startsWith("Compiling")
        ) {
          scribe.info(params.getMessage.toLowerCase())
        }
        for {
          task <- params.asCompileTask
          target = task.getTarget
          info <- buildTargets.info(target)
        } {
          diagnostics.onStartCompileBuildTarget(target)
          // cancel ongoing compilation for the current target, if any.
          compilations.remove(target).foreach(_.end())

          val name = info.getDisplayName
          val owner = buildTargets.buildServerOf(target)
          val (_, token) =
            workDoneProgress.startProgress(
              s"Compiling $name",
              withProgress = true,
            )
          val compilation = new Compilation(
            new Timer(time),
            owner,
            Option(params.getOriginId()),
            Option(params.getTaskId()).map(_.getId()),
            token,
          )
          compilations(target) = compilation
          // This `build/taskStart` may have raced connection/session teardown
          // or its own request's terminal cleanup on another thread; both
          // update their flags/tombstones before scanning, so re-checking
          // after the insert closes the race (see scalameta/metals#3464).
          val finishedRequest = compilation.originId match {
            case Some(origin) => tombstonedOrigins.contains(origin)
            case None => tombstonedTargets.contains(target.getUri())
          }
          val closingOwner = owner match {
            case Some(connection) => connection.shutdownInitiated
            case None => !hasBuildSession()
          }
          val isStale = finishedRequest || closingOwner
          if (isStale && compilations.remove(target, compilation)) {
            compilation.end()
          }
        }
      case _ =>
    }
  }

  @JsonNotification("build/taskFinish")
  def buildTaskFinish(params: b.TaskFinishParams): Unit = {
    params.getDataKind match {
      case b.TaskFinishDataKind.COMPILE_REPORT =>
        for {
          report <- params.asCompileReport
        } {
          val target = report.getTarget
          // the progress might have already been ended, e.g. when the
          // connection was lost, but the report is still worth processing
          val compilation = compilations.remove(target)
          try {
            diagnostics.onFinishCompileBuildTarget(
              report,
              params.getStatus(),
              params.getOriginId(),
            )
            moduleStatus.onFinishCompileBuildTarget(target)
            try {
              didCompile(report)
            } catch {
              case NonFatal(e) =>
                scribe.error(s"failed to process compile report", e)
            }
          } finally {
            compilation.foreach(_.end())
          }
          val name = buildTargets.info(target) match {
            case Some(i) => i.getDisplayName
            case None => target.getUri
          }
          val isSuccess = report.getErrors == 0
          val icon =
            if (isSuccess) clientConfig.icons().check
            else clientConfig.icons().alert
          val timeTaken = compilation.map(c => s" (${c.timer})").getOrElse("")
          val message = s"${icon}Compiled $name$timeTaken"
          compilation.foreach { compilation =>
            if (report.getNoOp())
              scribe.debug(
                s"time: noop compilation of $name in ${compilation.timer}"
              )
            else
              scribe.info(s"time: compiled $name in ${compilation.timer}")
          }
          if (isSuccess) {
            if (hasReportedError.contains(target)) {
              // Only report success compilation if it fixes a previous compile error.
              statusBar.addMessage(message)
            }
            updatedTreeViews.add(target)
            onBuildTargetDidCompile(target)
            hasReportedError.remove(target)
          } else {
            hasReportedError.add(target)
            statusBar.addMessage(
              MetalsStatusParams(
                message
              )
            )
          }
        }
      case _ =>
    }
  }

  @JsonNotification("build/taskProgress")
  def buildTaskProgress(params: b.TaskProgressParams): Unit = {
    def buildTargetFromParams: Option[b.BuildTargetIdentifier] =
      for {
        data <- Option(params.getData).collect { case o: JsonObject =>
          o
        }
        targetElement <- Option(data.get("target"))
        if targetElement.isJsonObject
        target = targetElement.getAsJsonObject
        uriElement <- Option(target.get("uri"))
        if uriElement.isJsonPrimitive
        uri = uriElement.getAsJsonPrimitive
        if uri.isString
      } yield new b.BuildTargetIdentifier(uri.getAsString)

    // BSP allows progress with arbitrary or absent data, so refresh liveness
    // by task identity regardless of `dataKind`: the idle sweep must never
    // end a compilation still reporting activity of any shape
    Option(params.getTaskId()).foreach(touchTask)

    params.getDataKind match {
      case "bloop-progress" =>
        for {
          buildTarget <- buildTargetFromParams
          report <- compilations.get(buildTarget)
        } yield {
          report.updateProgress(params.getProgress, params.getTotal)
        }
      case "compile-progress" =>
        // "compile-progress" is from sbt, however its progress field is actually a percentage,
        // so we should fix the total to 100.
        for {
          buildTarget <- buildTargetFromParams
          report <- compilations.get(buildTarget)
        } yield {
          report.updateProgress(params.getProgress)
        }
      case _ =>
    }
  }
}
