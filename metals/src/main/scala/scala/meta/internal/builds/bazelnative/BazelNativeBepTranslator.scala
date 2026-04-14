package scala.meta.internal.builds.bazelnative

import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal

import ch.epfl.scala.bsp4j._
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.protobuf.UnknownFieldSet

class BazelNativeBepTranslator() {

  @volatile private var client: BazelNativeBspClient = _

  def setClient(c: BazelNativeBspClient): Unit = client = c

  private val gson = new Gson()
  private val activeTaskIds = new ConcurrentHashMap[String, TaskId]()
  @volatile private var currentOriginId: Option[String] = None
  @volatile private var currentTargets: List[BuildTargetIdentifier] = Nil

  private val ProgressPattern = """\[(\d+)\s*/\s*(\d+)\]""".r.unanchored

  def setOriginId(originId: String): Unit =
    currentOriginId = Some(originId)

  def setTargets(targets: List[BuildTargetIdentifier]): Unit =
    currentTargets = targets

  def clearState(): Unit = {
    currentOriginId = None
    currentTargets = Nil
  }

  def onRawStreamEvent(data: Array[Byte]): Array[Byte] =
    buildStreamResponse(data)

  /**
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

  def notifyBuildStarted(invocationId: String): Unit = {
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
          client.onBuildTaskStart(params)
        }
      }
    }
  }

  def notifyBuildFinished(invocationId: String, exitCode: Int): Unit = {
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

  def onBuildStderr(line: String): Unit = {
    line match {
      case ProgressPattern(completedStr, totalStr) =>
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

}

trait BazelNativeBspClient {
  def onBuildTaskStart(params: TaskStartParams): Unit
  def onBuildTaskFinish(params: TaskFinishParams): Unit
  def onBuildTaskProgress(params: TaskProgressParams): Unit
}
