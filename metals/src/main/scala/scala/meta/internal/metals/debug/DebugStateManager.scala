package scala.meta.internal.metals.debug

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.ContinuedEventArguments

/**
 * Manages the state of threads in debug sessions.
 * Tracks which threads are paused/running based on DAP events.
 */
class DebugStateManager {

  // Map of sessionId -> (threadId -> ThreadDebugState)
  private val sessionStates =
    new ConcurrentHashMap[String, ConcurrentHashMap[Int, ThreadDebugState]]()

  def onStopped(sessionId: String, event: StoppedEventArguments): Unit = {
    val threadStates = sessionStates.computeIfAbsent(
      sessionId,
      _ => new ConcurrentHashMap[Int, ThreadDebugState](),
    )

    if (event.getAllThreadsStopped) {
      // All threads are stopped
      threadStates.forEach((_, state) => {
        state.isPaused = true
        state.stopReason = Some(event.getReason)
      })
    } else {
      // Only specific thread is stopped
      val threadId = event.getThreadId
      val state =
        threadStates.computeIfAbsent(threadId, _ => ThreadDebugState(threadId))
      state.isPaused = true
      state.stopReason = Some(event.getReason)
      state.description = Option(event.getDescription)
    }
  }

  def onContinued(sessionId: String, event: ContinuedEventArguments): Unit = {
    val threadStates = sessionStates.get(sessionId)
    if (threadStates != null) {
      if (
        Option(event.getAllThreadsContinued)
          .map(_.booleanValue())
          .getOrElse(true)
      ) {
        // All threads continued
        threadStates.forEach((_, state) => {
          state.isPaused = false
          state.stopReason = None
          state.description = None
        })
      } else {
        // Only specific thread continued
        val threadId = event.getThreadId
        val state = threadStates.get(threadId)
        if (state != null) {
          state.isPaused = false
          state.stopReason = None
          state.description = None
        }
      }
    }
  }

  def onThreadStarted(sessionId: String, threadId: Int): Unit = {
    val threadStates = sessionStates.computeIfAbsent(
      sessionId,
      _ => new ConcurrentHashMap[Int, ThreadDebugState](),
    )
    threadStates.putIfAbsent(threadId, ThreadDebugState(threadId))
  }

  def onThreadExited(sessionId: String, threadId: Int): Unit = {
    val threadStates = sessionStates.get(sessionId)
    if (threadStates != null) {
      threadStates.remove(threadId)
    }
  }

  def getThreadState(
      sessionId: String,
      threadId: Int,
  ): Option[ThreadDebugState] = {
    Option(sessionStates.get(sessionId)).flatMap(m => Option(m.get(threadId)))
  }

  def getSessionThreadStates(sessionId: String): Map[Int, ThreadDebugState] = {
    Option(sessionStates.get(sessionId))
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
  }

  def clearSession(sessionId: String): Unit = {
    sessionStates.remove(sessionId)
  }

  def clearAll(): Unit = {
    sessionStates.clear()
  }
}

case class ThreadDebugState(
    threadId: Int,
    var isPaused: Boolean = false,
    var stopReason: Option[String] = None,
    var description: Option[String] = None,
) {
  def stateString: String = {
    if (isPaused) {
      val reason = stopReason.getOrElse("unknown")
      description match {
        case Some(desc) => s"paused ($reason: $desc)"
        case None => s"paused ($reason)"
      }
    } else {
      "running"
    }
  }
}
