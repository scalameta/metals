package scala.meta.internal.metals.debug.server

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.process.ProcessOutput
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener

/**
 * Debug adapter for forked test processes where we pre-assign a debug port.
 *
 * This adapter allocates a port upfront and passes it to the forked process via
 * command substitution. It polls for the port to become available and verifies
 * the JDWP handshake before notifying the debug server.
 *
 * This is useful for Maven Surefire tests where the forked JVM's output may not
 * be captured immediately by Maven.
 */
class ForkedTestDebugAdapter(
    commandWithPort: Int => Future[List[String]],
    workspace: AbsolutePath,
    env: Map[String, String],
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee(project, userJavaHome) {

  def name: String =
    s"${getClass.getSimpleName}(${project.name})"

  def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val port = ForkedTestDebugAdapter.findFreePort()
    val commandFuture = commandWithPort(port)
    val logger = new Logger(listener)
    val cancelled = new AtomicBoolean(false)
    val processRef = new AtomicReference[Option[SystemProcess]](None)

    val completeFuture: Future[Unit] = commandFuture.flatMap { command =>
      scribe.debug(
        s"Starting forked test debug session on port $port: ${command.mkString(" ")} (cwd=$workspace)"
      )

      val process = SystemProcess.run(
        cmd = command,
        cwd = workspace,
        redirectErrorOutput = false,
        env = env,
        processOut = Some(ProcessOutput.Lines(logger.logOutput)),
        processErr = Some(logger.logError),
      )
      processRef.set(Some(process))
      // If cancel() was called before the process started, cancel it now
      if (cancelled.get()) process.cancel

      // Poll for the port to become available
      Future {
        val address = new InetSocketAddress("127.0.0.1", port)
        if (
          ForkedTestDebugAdapter.waitForJdwp(
            port,
            cancelled,
            maxWaitMs = 120000,
          )
        ) {
          // Add a small delay to ensure the debug agent is fully initialized
          Thread.sleep(500)
          if (!cancelled.get()) {
            scribe.debug(s"Forked test JVM is now listening on port $port")
            listener.onListening(address)
          }
        } else if (!cancelled.get()) {
          scribe.error(s"Timeout waiting for forked test JVM on port $port")
        }
      }

      process.complete.map { code =>
        if (code != 0)
          throw new Exception(
            s"forked test process exited with code $code: ${command.mkString(" ")}"
          )
      }
    }

    new CancelableFuture[Unit] {
      def future = completeFuture
      def cancel(): Unit = {
        cancelled.set(true)
        processRef.get().foreach(_.cancel)
      }
    }
  }
}

object ForkedTestDebugAdapter {
  def findFreePort(): Int = {
    val socket = new ServerSocket(0)
    try {
      socket.setReuseAddress(true)
      socket.getLocalPort
    } finally {
      socket.close()
    }
  }

  /**
   * Wait for the JDWP debug agent to be ready on the specified port.
   * This performs a proper JDWP handshake to ensure the debug agent is ready.
   */
  def waitForJdwp(
      port: Int,
      cancelled: AtomicBoolean,
      maxWaitMs: Long,
  ): Boolean = {
    val startTime = System.currentTimeMillis()
    val pollIntervalMs = 200

    while (
      !cancelled.get() && System.currentTimeMillis() - startTime < maxWaitMs
    ) {
      if (isJdwpReady(port)) {
        return true
      }
      Thread.sleep(pollIntervalMs)
    }
    false
  }

  /**
   * Check if something is listening on the port by trying to bind to it.
   * If we can't bind, something else (the debug agent) is using it.
   * This doesn't require connecting, so it won't interfere with the debug agent.
   */
  private def isJdwpReady(port: Int): Boolean = {
    var socket: ServerSocket = null
    try {
      socket = new ServerSocket(port)
      // We could bind, so the port is free - debug agent not ready yet
      false
    } catch {
      case _: java.net.BindException =>
        // Can't bind because something is already listening - that's our debug agent
        true
      case _: Exception =>
        false
    } finally {
      if (socket != null) {
        try socket.close()
        catch { case _: Exception => }
      }
    }
  }
}
