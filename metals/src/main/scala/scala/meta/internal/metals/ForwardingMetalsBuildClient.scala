package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.TaskDataKind
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.{bsp4j => b}
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * A build client that forwards notifications from the build server to the language client.
 */
final class ForwardingMetalsBuildClient(
    languageClient: LanguageClient,
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    config: MetalsServerConfig,
    statusBar: StatusBar,
    time: Time
) extends MetalsBuildClient
    with Cancelable {

  private case class Compilation(
      timer: Timer,
      promise: Promise[CompileReport]
  )

  private val compilations = TrieMap.empty[BuildTargetIdentifier, Compilation]
  private val hasReportedError = Collections.newSetFromMap(
    new ConcurrentHashMap[BuildTargetIdentifier, java.lang.Boolean]()
  )

  def reset(): Unit = {
    cancel()
  }
  override def cancel(): Unit = {
    compilations.values.foreach { compilation =>
      compilation.promise.cancel()
    }
  }

  def onBuildShowMessage(params: l.MessageParams): Unit =
    languageClient.showMessage(params)

  def onBuildLogMessage(params: l.MessageParams): Unit =
    params.getType match {
      case l.MessageType.Error =>
        scribe.error(params.getMessage)
      case l.MessageType.Warning =>
        scribe.warn(params.getMessage)
      case l.MessageType.Info =>
        scribe.info(params.getMessage)
      case l.MessageType.Log =>
        scribe.info(params.getMessage)
    }

  def onBuildPublishDiagnostics(params: b.PublishDiagnosticsParams): Unit = {
    diagnostics.onBuildPublishDiagnostics(params)
  }

  def onBuildTargetDidChange(params: b.DidChangeBuildTarget): Unit = {
    scribe.info(params.toString)
  }

  def onBuildTargetCompileReport(params: b.CompileReport): Unit = {}

  @JsonNotification("build/taskStart")
  def buildTaskStart(params: TaskStartParams): Unit = {
    params.getDataKind match {
      case TaskDataKind.COMPILE_TASK =>
        for {
          task <- params.asCompileTask
          info <- buildTargets.info(task.getTarget)
        } {
          diagnostics.onStartCompileBuildTarget(task.getTarget)
          // cancel ongoing compilation for the current target, if any.
          compilations.get(task.getTarget).foreach(_.promise.cancel())

          val name = info.getDisplayName
          val promise = Promise[CompileReport]()
          val compilation = Compilation(new Timer(time), promise)

          compilations(task.getTarget) = compilation
          statusBar.trackFuture(
            s"Compiling $name",
            promise.future,
            showTimer = true
          )
        }
      case _ =>
    }
  }

  @JsonNotification("build/taskFinish")
  def buildTaskFinish(params: TaskFinishParams): Unit = {
    params.getDataKind match {
      case TaskDataKind.COMPILE_REPORT =>
        for {
          report <- params.asCompileReport
          compilation <- compilations.get(report.getTarget)
        } {
          diagnostics.onFinishCompileBuildTarget(report.getTarget)
          val target = report.getTarget
          compilation.promise.trySuccess(report)
          val name = buildTargets.info(report.getTarget) match {
            case Some(i) => i.getDisplayName
            case None => report.getTarget.getUri
          }
          val isSuccess = report.getErrors == 0
          val icon = if (isSuccess) config.icons.check else config.icons.alert
          val message = s"${icon}Compiled $name (${compilation.timer})"
          if (isSuccess) {
            if (hasReportedError.contains(target)) {
              // Only report success compilation if it fixes a previous compile error.
              statusBar.addMessage(message)
            }
            hasReportedError.remove(target)
          } else {
            hasReportedError.add(target)
            statusBar.addMessage(
              MetalsStatusParams(
                message,
                command = ClientCommands.FocusDiagnostics.id
              )
            )
          }
        }
      case _ =>
    }
  }

  @JsonNotification("build/taskProgress")
  def buildTaskProgress(params: TaskProgressParams): Unit = {}
}
