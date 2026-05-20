package scala.meta.internal.metals

import java.lang.management.ManagementFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType

/**
 * Monitors JVM memory usage and warns users when memory is running low.
 * This helps prevent Metals from becoming unreliable due to insufficient memory.
 */
class MemoryMonitor(
    languageClient: MetalsLanguageClient,
    sh: ScheduledExecutorService,
) {

  private val memoryBean = ManagementFactory.getMemoryMXBean()
  private val warningShown = new AtomicBoolean(false)
  private var scheduledTask: Option[ScheduledFuture[_]] = None

  // Threshold: warn when heap usage exceeds 90% of max heap
  private val warningThreshold = 0.90

  // Check interval in seconds
  private val checkIntervalSeconds = 30L

  def start(): Unit = {
    val task = sh.scheduleAtFixedRate(
      () => checkMemory(),
      checkIntervalSeconds,
      checkIntervalSeconds,
      TimeUnit.SECONDS,
    )
    scheduledTask = Some(task)
    scribe.info("Memory monitor started")
  }

  def stop(): Unit = {
    scheduledTask.foreach(_.cancel(false))
    scheduledTask = None
    scribe.info("Memory monitor stopped")
  }

  private def checkMemory(): Unit = {
    try {
      val heapUsage = memoryBean.getHeapMemoryUsage()
      val used = heapUsage.getUsed()
      val max = heapUsage.getMax()

      // max can be -1 if not defined
      if (max > 0) {
        val usageRatio = used.toDouble / max.toDouble

        if (
          usageRatio >= warningThreshold && warningShown.compareAndSet(
            false,
            true,
          )
        ) {
          val usedMB = used / (1024 * 1024)
          val maxMB = max / (1024 * 1024)
          showMemoryWarning(usedMB, maxMB, usageRatio)
        } else if (usageRatio < warningThreshold * 0.8) {
          // Reset warning flag if memory usage drops significantly
          warningShown.set(false)
        }
      }
    } catch {
      case e: Exception =>
        scribe.warn(s"Error checking memory usage: ${e.getMessage}")
    }
  }

  private def showMemoryWarning(
      usedMB: Long,
      maxMB: Long,
      usageRatio: Double,
  ): Unit = {
    val percentage = (usageRatio * 100).toInt
    val message =
      s"""|Metals is using $percentage% of available memory ($usedMB MB / $maxMB MB).
          |
          |This may cause performance issues or instability.
          |
          |To increase memory, add -Xmx2G (or higher) to your Metals server options:
          |  - VS Code: Set "metals.serverProperties": ["-Xmx2G"]
          |  - Other editors: Check your editor's Metals configuration
          |""".stripMargin

    scribe.warn(
      s"High memory usage detected: $percentage% ($usedMB MB / $maxMB MB)"
    )
    languageClient.showMessage(new MessageParams(MessageType.Warning, message))
  }
}
