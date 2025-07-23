package scala.meta.internal.metals.clients.language

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.builds.BspErrorHandler
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
      token: Future[WorkDoneProgress.Token],
      taskProgress: TaskProgress = TaskProgress.empty,
  ) {

    def progressPercentage = taskProgress.percentage

    def end(): Unit = workDoneProgress.endProgress(token)

    def updateProgress(progress: Long, total: Long = 100): Unit = {
      val prev = taskProgress.percentage
      taskProgress.update(progress, total)
      if (prev != taskProgress.percentage) {
        workDoneProgress.notifyProgress(token, progressPercentage)
      }
    }
  }

  private val compilations = TrieMap.empty[b.BuildTargetIdentifier, Compilation]
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

  override def cancel(): Unit = {
    for {
      key <- compilations.keysIterator
      compilation <- compilations.remove(key)
    } {
      compilation.end()
    }
  }

  def onBuildShowMessage(params: l.MessageParams): Unit =
    languageClient.showMessage(params)

  def onBuildLogMessage(params: l.MessageParams): Unit = {
    // NOTE: BazelBsp adds coloring to the log message after `workspaceBuildTargets` request
    val noANSICodes = filterANSIColorCodes(params.getMessage).trim()
    if (noANSICodes.nonEmpty) {
      params.getType match {
        case l.MessageType.Error =>
          bspErrorHandler.onError(noANSICodes)
          forwarders.get().foreach(_.error(params.getMessage()))
        case l.MessageType.Warning =>
          forwarders.get().foreach(_.warn(params.getMessage()))
          scribe.warn(noANSICodes)
        case l.MessageType.Info =>
          forwarders.get().foreach(_.info(params.getMessage()))
          scribe.info(noANSICodes)
        case l.MessageType.Log =>
          forwarders.get().foreach(_.log(params.getMessage()))
          scribe.info(noANSICodes)
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
          val (_, token) =
            workDoneProgress.startProgress(
              s"Compiling $name",
              withProgress = true,
            )
          val compilation = new Compilation(new Timer(time), token)
          compilations(task.getTarget) = compilation
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
          compilation <- compilations.remove(report.getTarget)
        } {
          diagnostics.onFinishCompileBuildTarget(
            report,
            params.getStatus(),
            params.getOriginId(),
          )
          moduleStatus.onFinishCompileBuildTarget(report.getTarget)
          try {
            didCompile(report)
          } catch {
            case NonFatal(e) =>
              scribe.error(s"failed to process compile report", e)
          }
          val target = report.getTarget
          compilation.end()
          val name = buildTargets.info(report.getTarget) match {
            case Some(i) => i.getDisplayName
            case None => report.getTarget.getUri
          }
          val isSuccess = report.getErrors == 0
          val icon =
            if (isSuccess) clientConfig.icons().check
            else clientConfig.icons().alert
          val message = s"${icon}Compiled $name (${compilation.timer})"
          if (report.getNoOp())
            scribe.debug(
              s"time: noop compilation of $name in ${compilation.timer}"
            )
          else
            scribe.info(s"time: compiled $name in ${compilation.timer}")
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
