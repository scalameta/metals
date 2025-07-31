package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.duration.FiniteDuration

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * A reconciling strategy that buffers text edits that happen in a burst and triggers
 * presentation compiler diagnostics only after a configured delay of quietness. This
 * makes the error squigglies flicker less and also solves race conditions if diagnostics
 * are reported from different futures, running on different threads in a pool.
 */
class DiagnosticsDebouncer(
    buffers: Buffers,
    diagnostics: Diagnostics,
    compilers: Compilers,
    sh: ScheduledExecutorService,
    delay: FiniteDuration,
) extends Cancelable {

  @volatile
  private var processFut: ScheduledFuture[Unit] =
    sh.schedule(processChanges _, delay.toMillis, TimeUnit.MILLISECONDS)

  @volatile
  private var cancelled = false

  private val processing = new AtomicBoolean(false)

  def rescheduleDiagnostics(): Unit = synchronized {
    processFut.cancel( /* mayInterruptIfRunning = */ false)
    processFut =
      sh.schedule(processChanges _, delay.toMillis, TimeUnit.MILLISECONDS)
  }

  private val queue: ConcurrentLinkedQueue[ChangedFile] =
    new ConcurrentLinkedQueue()

  def didChange(documentVersion: Int, path: AbsolutePath): Unit = {
    queue.removeIf(change =>
      change.path == path && change.documentVersion < documentVersion
    )
    queue.add(ChangedFile(documentVersion, path))
    rescheduleDiagnostics()
  }

  def processChanges(): Unit = {
    if (!cancelled && processing.compareAndSet(false, true)) {
      try {
        while (!queue.isEmpty()) {
          val ChangedFile(_, path) = queue.poll()
          for (
            pc <- compilers.loadCompiler(path);
            contents <- buffers.get(path)
          ) yield {
            val diags = pc
              .didChange(
                CompilerVirtualFileParams(path.toNIO.toUri, contents)
              )
              .get()
            diagnostics.publishDiagnosticsNotAdjusted(
              path,
              diags.asScala.toList,
            )
          }
        }
      } finally {
        // can't really happen since this is the only method that grabs the lock
        assert(processing.compareAndSet(true, false))
      }
    }
  }

  override def cancel(): Unit = {
    cancelled = true
    processFut.cancel( /* mayInterruptIfRunning = */ false)
  }

  case class ChangedFile(documentVersion: Int, path: AbsolutePath)
}
