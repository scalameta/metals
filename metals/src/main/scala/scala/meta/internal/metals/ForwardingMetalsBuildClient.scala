package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.TaskDataKind
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import scala.meta.internal.metals.MetalsEnrichments._
import java.util.concurrent.atomic.AtomicBoolean
import scala.meta.internal.tvp._

/**
 * A build client that forwards notifications from the build server to the language client.
 */
final class ForwardingMetalsBuildClient(
    languageClient: MetalsLanguageClient,
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    config: MetalsServerConfig,
    statusBar: StatusBar,
    time: Time,
    didCompile: CompileReport => Unit,
    treeViewProvider: () => TreeViewProvider
) extends MetalsBuildClient
    with Cancelable {

  private case class Compilation(
      timer: Timer,
      promise: Promise[CompileReport],
      isNoOp: Boolean,
      progress: TaskProgress = TaskProgress.empty
  )

  private val compilations = TrieMap.empty[BuildTargetIdentifier, Compilation]
  private val hasReportedError = Collections.newSetFromMap(
    new ConcurrentHashMap[BuildTargetIdentifier, java.lang.Boolean]()
  )

  def reset(): Unit = {
    cancel()
  }

  override def cancel(): Unit = {
    for {
      key <- compilations.keysIterator
      compilation <- compilations.remove(key)
    } {
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
        if (params.getMessage.startsWith("Compiling")) {
          scribe.info(params.getMessage.toLowerCase())
        }
        for {
          task <- params.asCompileTask
          info <- buildTargets.info(task.getTarget)
        } {
          diagnostics.onStartCompileBuildTarget(task.getTarget)
          // cancel ongoing compilation for the current target, if any.
          compilations.remove(task.getTarget).foreach(_.promise.cancel())

          val name = info.getDisplayName
          val promise = Promise[CompileReport]()
          val isNoOp = params.getMessage.startsWith("Start no-op compilation")
          val compilation = Compilation(new Timer(time), promise, isNoOp)

          compilations(task.getTarget) = compilation
          statusBar.trackFuture(
            s"Compiling $name",
            promise.future,
            showTimer = true,
            progress = Some(compilation.progress)
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
          compilation <- compilations.remove(report.getTarget)
        } {
          diagnostics.onFinishCompileBuildTarget(report.getTarget)
          didCompile(report)
          val target = report.getTarget
          compilation.promise.trySuccess(report)
          val name = buildTargets.info(report.getTarget) match {
            case Some(i) => i.getDisplayName
            case None => report.getTarget.getUri
          }
          val isSuccess = report.getErrors == 0
          val icon = if (isSuccess) config.icons.check else config.icons.alert
          val message = s"${icon}Compiled $name (${compilation.timer})"
          if (!compilation.isNoOp) {
            scribe.info(s"time: compiled $name in ${compilation.timer}")
          }
          if (isSuccess) {
            if (hasReportedError.contains(target)) {
              // Only report success compilation if it fixes a previous compile error.
              statusBar.addMessage(message)
            }
            if (!compilation.isNoOp) {
              treeViewProvider().onBuildTargetDidCompile(report.getTarget())
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
  def buildTaskProgress(params: TaskProgressParams): Unit = {
    params.getDataKind match {
      case "bloop-progress" =>
        for {
          data <- Option(params.getData).collect {
            case o: JsonObject => o
          }
          targetElement <- Option(data.get("target"))
          if targetElement.isJsonObject
          target = targetElement.getAsJsonObject
          uriElement <- Option(target.get("uri"))
          if uriElement.isJsonPrimitive
          uri = uriElement.getAsJsonPrimitive
          if uri.isString
          buildTarget = new BuildTargetIdentifier(uri.getAsString)
          report <- compilations.get(buildTarget)
        } yield {
          report.progress.update(params.getProgress, params.getTotal)
        }
      case _ =>
    }
  }

  def ongoingCompilations: Array[TreeViewNode] = {
    compilations.keysIterator.flatMap(ongoingCompileNode).toArray
  }

  def ongoingCompileNode(
      id: BuildTargetIdentifier
  ): Option[TreeViewNode] = {
    for {
      compilation <- compilations.get(id)
      info <- buildTargets.info(id)
    } yield
      TreeViewNode(
        "compile",
        id.getUri,
        s"${info.getDisplayName()} - ${compilation.timer.toStringSeconds} (${compilation.progress.percentage}%)"
      )
  }

  def ongoingCompilationNode: TreeViewNode = {
    val size = compilations.size
    val counter = if (size > 0) s" ($size)" else ""
    TreeViewNode(
      "compile",
      "metals://ongoing-compilations",
      s"Ongoing compilations$counter",
      collapseState = MetalsTreeItemCollapseState.collapsed
    )
  }

  def toplevelTreeNodes: Array[TreeViewNode] =
    Array(ongoingCompilationNode)

  private val wasEmpty = new AtomicBoolean(true)
  private val isEmpty = new AtomicBoolean(true)
  def tickBuildTreeView(): Unit = {
    isEmpty.set(compilations.isEmpty)
    if (wasEmpty.get() && isEmpty.get()) {
      () // Nothing changed since the last notification.
    } else {
      languageClient.metalsTreeViewDidChange(
        TreeViewDidChangeParams(toplevelTreeNodes)
      )
    }
    wasEmpty.set(isEmpty.get())
  }
}
