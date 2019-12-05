package scala.meta.internal.process

import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import scala.concurrent.Promise
import scala.meta.internal.ansi.LineListener

object ProcessHandler {
  def apply(
      joinErrorWithInfo: Boolean
  ): ProcessHandler = {
    val stdout = new LineListener(line => scribe.info(line))
    val stderr =
      if (joinErrorWithInfo) stdout
      else new LineListener(line => scribe.error(line))
    new ProcessHandler(stdout, stderr)
  }

  /**
   * First tries to destroy the process gracefully, with fallback to forcefully.
   */
  def destroyProcess(process: NuProcess): Unit = {
    process.destroy(false)
    val exit = process.waitFor(2, TimeUnit.SECONDS)
    if (exit == Integer.MIN_VALUE) {
      // timeout exceeded, kill process forcefully.
      process.destroy(true)
      process.waitFor(2, TimeUnit.SECONDS)
    }
  }
}

/**
 * Converts running system processing into Future[BloopInstallResult].
 */
class ProcessHandler(
    val stdout: LineListener,
    val stderr: LineListener
) extends NuAbstractProcessHandler {
  var response: Option[CompletableFuture[_]] = None
  val completeProcess: Promise[Int] =
    Promise[Int]()

  override def onStart(nuProcess: NuProcess): Unit = {
    nuProcess.closeStdin(false)
  }

  override def onExit(statusCode: Int): Unit = {
    stdout.flushIfNonEmpty()
    stderr.flushIfNonEmpty()
    if (!completeProcess.isCompleted) {
      completeProcess.trySuccess(statusCode)
    }
    scribe.info(s"build tool exit: $statusCode")
    response.foreach(_.cancel(false))
  }

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (!closed) {
      stdout.appendBytes(buffer)
    }
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (!closed) {
      stderr.appendBytes(buffer)
    }
  }
}
