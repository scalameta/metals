package scala.meta.internal.builds.bazelnative

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.protobuf.UnknownFieldSet

/**
 * Translates BES (Build Event Service) raw protobuf messages into BSP notifications.
 *
 * The BES transport layer provides lifecycle events via `PublishLifecycleEvent`
 * and build tool events via `PublishBuildToolEventStream`.
 *
 * Build lifecycle (start/finish) produces properly-typed BSP notifications with
 * `COMPILE_TASK` / `COMPILE_REPORT` data kinds so that Metals shows progress
 * indicators and status bar updates.
 *
 * Progress is derived from Bazel's stderr output: lines matching `[N / M]` are
 * translated into `build/taskProgress` BSP notifications.
 */
class BazelNativeBepTranslator(client: BazelNativeBspClient) {

  private val gson = new Gson()
  private val activeTaskIds = new ConcurrentHashMap[String, TaskId]()
  @volatile private var currentOriginId: Option[String] = None
  @volatile private var currentTargets: List[BuildTargetIdentifier] = Nil

  private val ProgressPattern = """\[(\d+)\s*/\s*(\d+)\]""".r.unanchored

  def setOriginId(originId: String): Unit =
    currentOriginId = Some(originId)

  def clearOriginId(): Unit =
    currentOriginId = None

  def setTargets(targets: List[BuildTargetIdentifier]): Unit =
    currentTargets = targets

  def clearState(): Unit = {
    currentOriginId = None
    currentTargets = Nil
  }

  /**
   * Handle a raw `PublishLifecycleEventRequest` protobuf message.
   * We extract just enough to know when a build starts/finishes.
   */
  def onRawLifecycleEvent(data: Array[Byte]): Unit = {
    scribe.debug(
      s"[BazelNative BEP] Lifecycle event (${data.length} bytes)"
    )
  }

  /**
   * Handle a raw `PublishBuildToolEventStreamRequest` protobuf message.
   * Returns a raw `PublishBuildToolEventStreamResponse`.
   */
  def onRawStreamEvent(data: Array[Byte]): Array[Byte] = {
    scribe.debug(
      s"[BazelNative BEP] Stream event (${data.length} bytes)"
    )

    if (client != null) {
      val logParams = new LogMessageParams(
        MessageType.LOG,
        s"Received BES event (${data.length} bytes)",
      )
      client.onBuildLogMessage(logParams)
    }

    buildStreamResponse(data)
  }

  /**
   * Parse the `PublishBuildToolEventStreamRequest` and build a
   * `PublishBuildToolEventStreamResponse` echoing back the `stream_id`
   * and `sequence_number` from the request's `ordered_build_event`.
   *
   * Bazel validates that the ACK `sequence_number` matches the request;
   * returning an empty response (seqNum=0) causes `FAILED_PRECONDITION`.
   */
  private def buildStreamResponse(request: Array[Byte]): Array[Byte] = {
    try {
      val parsed = UnknownFieldSet.parseFrom(request)
      val orderedEventField = parsed.getField(4)
      if (
        orderedEventField != null &&
        !orderedEventField.getLengthDelimitedList.isEmpty
      ) {
        val orderedEvent = UnknownFieldSet.parseFrom(
          orderedEventField.getLengthDelimitedList.get(0)
        )
        val responseBuilder = UnknownFieldSet.newBuilder()

        val streamIdField = orderedEvent.getField(1)
        if (
          streamIdField != null &&
          !streamIdField.getLengthDelimitedList.isEmpty
        ) {
          responseBuilder.addField(1, streamIdField)
        }

        val seqNumField = orderedEvent.getField(2)
        if (seqNumField != null && !seqNumField.getVarintList.isEmpty) {
          responseBuilder.addField(2, seqNumField)
        }

        responseBuilder.build().toByteArray
      } else {
        Array.emptyByteArray
      }
    } catch {
      case NonFatal(e) =>
        scribe.error("[BazelNative BEP] Failed to parse stream event", e)
        Array.emptyByteArray
    }
  }

  /**
   * Signal that a build has started. Called by [[BazelNativeBspServer]]
   * before launching the Bazel build command.
   *
   * Emits one `build/taskStart` per target with `COMPILE_TASK` data kind
   * so that Metals initializes its per-target progress tracker.
   */
  def notifyBuildStarted(invocationId: String): Unit = {
    scribe.info(
      s"[BazelNative BEP] Build started: $invocationId, targets=${currentTargets.size}"
    )

    if (currentTargets.isEmpty) {
      val taskId = new TaskId(invocationId)
      activeTaskIds.put(invocationId, taskId)
      if (client != null) {
        val params = new TaskStartParams(taskId)
        params.setEventTime(System.currentTimeMillis())
        params.setMessage("Bazel build started")
        currentOriginId.foreach(params.setOriginId)
        client.onBuildTaskStart(params)
      }
    } else {
      currentTargets.foreach { target =>
        val key = taskKey(invocationId, target)
        val taskId = new TaskId(key)
        activeTaskIds.put(key, taskId)

        if (client != null) {
          val compileTask = new CompileTask(target)
          val params = new TaskStartParams(taskId)
          params.setEventTime(System.currentTimeMillis())
          params.setMessage("Bazel build started")
          params.setDataKind(TaskStartDataKind.COMPILE_TASK)
          params.setData(gson.toJsonTree(compileTask))
          currentOriginId.foreach(params.setOriginId)
          scribe.info(
            s"[BazelNative BEP] Sending build/taskStart COMPILE_TASK for ${target.getUri}"
          )
          client.onBuildTaskStart(params)
        }
      }
    }
  }

  /**
   * Signal that a build has finished. Called by [[BazelNativeBspServer]]
   * after the Bazel build command completes.
   *
   * Emits one `build/taskFinish` per target with `COMPILE_REPORT` data kind
   * so that Metals finalizes diagnostics, updates the status bar, and
   * refreshes presentation compiler caches.
   */
  def notifyBuildFinished(invocationId: String, exitCode: Int): Unit = {
    scribe.info(
      s"[BazelNative BEP] Build finished: $invocationId, exitCode=$exitCode, targets=${currentTargets.size}"
    )
    val status = if (exitCode == 0) StatusCode.OK else StatusCode.ERROR

    if (currentTargets.isEmpty) {
      val taskId = Option(activeTaskIds.remove(invocationId))
        .getOrElse(new TaskId(invocationId))
      if (client != null) {
        val params = new TaskFinishParams(taskId, status)
        params.setEventTime(System.currentTimeMillis())
        params.setMessage(s"Bazel build finished (exit code: $exitCode)")
        currentOriginId.foreach(params.setOriginId)
        client.onBuildTaskFinish(params)
      }
    } else {
      currentTargets.foreach { target =>
        val key = taskKey(invocationId, target)
        val taskId = Option(activeTaskIds.remove(key))
          .getOrElse(new TaskId(key))

        if (client != null) {
          val errors: Integer = if (exitCode == 0) 0 else 1
          val report = new CompileReport(target, errors, 0)

          val params = new TaskFinishParams(taskId, status)
          params.setEventTime(System.currentTimeMillis())
          params.setMessage(s"Bazel build finished (exit code: $exitCode)")
          params.setDataKind(TaskFinishDataKind.COMPILE_REPORT)
          params.setData(gson.toJsonTree(report))
          currentOriginId.foreach(params.setOriginId)
          client.onBuildTaskFinish(params)
        }
      }
    }
  }

  /**
   * Parse Bazel's stderr for progress lines of the form `[N / M]` and
   * emit `build/taskProgress` BSP notifications.
   *
   * Progress is reported against the first target in [[currentTargets]]
   * because Bazel's `[N / M]` counter is global across all actions.
   */
  def onBuildStderr(line: String): Unit = {
    line match {
      case ProgressPattern(completedStr, totalStr) =>
        scribe.info(
          s"[BazelNative BEP] Progress: [$completedStr / $totalStr]"
        )
        currentTargets.headOption.foreach { target =>
          val invocationId = currentOriginId.getOrElse("unknown")
          val key = taskKey(invocationId, target)
          val taskId = Option(activeTaskIds.get(key))
            .getOrElse(new TaskId(key))

          if (client != null) {
            val data = new JsonObject()
            val targetObj = new JsonObject()
            targetObj.addProperty("uri", target.getUri)
            data.add("target", targetObj)

            val params = new TaskProgressParams(taskId)
            params.setEventTime(System.currentTimeMillis())
            params.setMessage(line.trim)
            params.setDataKind("bazel-progress")
            params.setData(data)
            params.setProgress(completedStr.toLong)
            params.setTotal(totalStr.toLong)
            client.onBuildTaskProgress(params)
          }
        }
      case _ =>
    }
  }

  private def taskKey(
      invocationId: String,
      target: BuildTargetIdentifier,
  ): String =
    s"$invocationId-${target.getUri}"

  /**
   * Parse compiler diagnostics from Bazel stderr output lines.
   * Typical format: `path/to/file.scala:line:col: error: message`
   */
  def parseDiagnosticsFromStderr(
      lines: List[String],
      buildTarget: BuildTargetIdentifier,
  ): Unit = {
    val diagnosticPattern =
      """^(.+):(\d+):(\d+): (error|warning|info): (.+)$""".r

    lines.foreach {
      case diagnosticPattern(filePath, lineStr, colStr, severity, message) =>
        val line = lineStr.toInt - 1
        val col = colStr.toInt - 1
        val bspSeverity = severity match {
          case "error" => DiagnosticSeverity.ERROR
          case "warning" => DiagnosticSeverity.WARNING
          case _ => DiagnosticSeverity.INFORMATION
        }
        val range = new Range(new Position(line, col), new Position(line, col))
        val diagnostic = new Diagnostic(range, message)
        diagnostic.setSeverity(bspSeverity)

        val uri = resolveFileUri(filePath)
        val textDoc = new TextDocumentIdentifier(uri)
        val params = new PublishDiagnosticsParams(
          textDoc,
          buildTarget,
          List(diagnostic).asJava,
          true,
        )
        currentOriginId.foreach(params.setOriginId)

        scribe.debug(
          s"[BazelNative BEP] Diagnostic: $filePath:${line + 1}:${col + 1} $severity: $message"
        )
        if (client != null) {
          client.onBuildPublishDiagnostics(params)
        }
      case _ => // not a diagnostic line
    }
  }

  private def resolveFileUri(path: String): String = {
    if (path.startsWith("/")) {
      new URI("file", null, path, null).toString
    } else {
      path
    }
  }
}

/**
 * Callback interface for the BEP translator to push BSP notifications.
 * Implemented by [[BazelNativeBspServer]] which forwards to the BSP client.
 */
trait BazelNativeBspClient {
  def onBuildTaskStart(params: TaskStartParams): Unit
  def onBuildTaskFinish(params: TaskFinishParams): Unit
  def onBuildTaskProgress(params: TaskProgressParams): Unit
  def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit
  def onBuildLogMessage(params: LogMessageParams): Unit
}
