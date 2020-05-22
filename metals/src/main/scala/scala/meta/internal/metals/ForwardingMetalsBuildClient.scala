package scala.meta.internal.metals

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.{util => ju}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ammonite.Ammonite
import scala.meta.internal.tvp._
import scala.meta.internal.worksheets.WorksheetProvider

import ch.epfl.scala.bsp4j._
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.{lsp4j => l}

/**
 * A build client that forwards notifications from the build server to the language client.
 */
final class ForwardingMetalsBuildClient(
    languageClient: MetalsLanguageClient,
    diagnostics: Diagnostics,
    buildTargets: BuildTargets,
    buildTargetClasses: BuildTargetClasses,
    clientConfig: ClientConfiguration,
    statusBar: StatusBar,
    time: Time,
    didCompile: CompileReport => Unit,
    treeViewProvider: () => TreeViewProvider,
    worksheetProvider: () => WorksheetProvider,
    ammonite: () => Ammonite
)(implicit ec: ExecutionContext)
    extends MetalsBuildClient
    with Cancelable {

  private case class Compilation(
      timer: Timer,
      promise: Promise[CompileReport],
      isNoOp: Boolean,
      progress: TaskProgress = TaskProgress.empty
  ) extends TreeViewCompilation {
    def progressPercentage = progress.percentage
  }

  private val compilations = TrieMap.empty[BuildTargetIdentifier, Compilation]
  private val hasReportedError = Collections.newSetFromMap(
    new ConcurrentHashMap[BuildTargetIdentifier, java.lang.Boolean]()
  )

  val updatedTreeViews: ju.Set[BuildTargetIdentifier] =
    ConcurrentHashSet.empty[BuildTargetIdentifier]

  def buildHasErrors(buildTargetId: BuildTargetIdentifier): Boolean = {
    buildTargets
      .buildTargetTransitiveDependencies(buildTargetId)
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
    val ammoniteBuildChanged =
      params.getChanges.asScala.exists(_.getTarget.getUri.isAmmoniteScript)
    if (ammoniteBuildChanged)
      ammonite().importBuild().onComplete {
        case Success(()) =>
        case Failure(exception) =>
          scribe.error("Error re-importing Ammonite build", exception)
      }
    else
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
          target = task.getTarget
          info <- buildTargets.info(target)
        } {
          diagnostics.onStartCompileBuildTarget(target)
          // cancel ongoing compilation for the current target, if any.
          compilations.remove(target).foreach(_.promise.cancel())

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
          val icon =
            if (isSuccess) clientConfig.initialConfig.icons.check
            else clientConfig.initialConfig.icons.alert
          val message = s"${icon}Compiled $name (${compilation.timer})"
          if (!compilation.isNoOp) {
            scribe.info(s"time: compiled $name in ${compilation.timer}")
          }
          if (isSuccess) {
            if (hasReportedError.contains(target)) {
              // Only report success compilation if it fixes a previous compile error.
              statusBar.addMessage(message)
            }
            if (!compilation.isNoOp || !updatedTreeViews.contains(target)) {
              // By default, skip `onBuildTargetDidCompile` notifications on no-op
              // compilations to reduce noisy traffic to the client. However, we
              // send the notification if it's the first successful compilation of
              // that target to fix
              // https://github.com/scalameta/metals/issues/846.
              updatedTreeViews.add(target)
              treeViewProvider().onBuildTargetDidCompile(target)
              worksheetProvider().onBuildTargetDidCompile(target)
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

  def ongoingCompilations(): TreeViewCompilations =
    new TreeViewCompilations {
      override def get(id: BuildTargetIdentifier) = compilations.get(id)
      override def isEmpty = compilations.isEmpty
      override def size = compilations.size
      override def buildTargets = compilations.keysIterator
    }
}
