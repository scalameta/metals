package scala.meta.internal.metals

import java.lang.management.ManagementFactory
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters._

import scala.meta.infra.Event
import scala.meta.infra.MonitoringClient

class MemoryMonitoring(
    initialServerConfig: MetalsServerConfig,
    metrics: MonitoringClient,
    loadedCompilerCount: () => Int,
    workspaceName: String,
    sh: ScheduledExecutorService,
) extends Cancelable {

  private val IntervalSeconds = 60L
  private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala
  @volatile private var lastGcTimeMillis: Long = totalGcTime()
  @volatile private var lastTickMillis: Long = System.currentTimeMillis()

  private def totalGcTime(): Long =
    gcBeans.map(_.getCollectionTime).filter(_ >= 0).sum

  private val scheduled: ScheduledFuture[_ <: Object] =
    sh.scheduleAtFixedRate(
      () => publishMetrics(),
      IntervalSeconds,
      IntervalSeconds,
      TimeUnit.SECONDS,
    )

  private def publishMetrics(): Unit = {
    val runtime = Runtime.getRuntime
    val usedHeap = runtime.totalMemory() - runtime.freeMemory()
    val committedHeap = runtime.totalMemory()
    val compilerCount = loadedCompilerCount()
    val pcThreadCount = Thread
      .getAllStackTraces()
      .keySet
      .asScala
      .count(_.getName.contains("Scala Presentation Compiler"))

    val now = System.currentTimeMillis()
    val currentGcTime = totalGcTime()
    val elapsedMs = math.max(now - lastTickMillis, 1)
    val gcDeltaMs = currentGcTime - lastGcTimeMillis
    val gcPercent = (gcDeltaMs.toFloat / elapsedMs.toFloat) * 100f
    lastGcTimeMillis = currentGcTime
    lastTickMillis = now

    // memory statistics are not included in All because they are expensive to compute for indexes
    // but these metrics are very cheap to collect
    if (
      initialServerConfig.statistics.isMemory || initialServerConfig.statistics.isAll
    ) {
      scribe.info(
        s"MemoryMonitoring: workspace=$workspaceName, heapUsed=${Memory.approx(usedHeap)}, heapCommitted=${Memory.approx(committedHeap)}, " +
          s"presentationCompilers=$compilerCount, pcThreads=$pcThreadCount, gcPercent=${"%.2f".format(gcPercent)}%"
      )
    }
    metrics.recordEvent(
      new Event()
        .withLabel("name", "heap_used_bytes")
        .withLabel("value", usedHeap.toString)
        .withLabel("workspace", workspaceName)
    )
    metrics.recordEvent(
      new Event()
        .withLabel("name", "heap_committed_bytes")
        .withLabel("value", committedHeap.toString)
        .withLabel("workspace", workspaceName)
    )
    metrics.recordEvent(
      new Event()
        .withLabel("name", "live_presentation_compilers")
        .withLabel("value", compilerCount.toString)
        .withLabel("workspace", workspaceName)
    )
    metrics.recordEvent(
      new Event()
        .withLabel("name", "scala_pc_threads")
        .withLabel("value", pcThreadCount.toString)
        .withLabel("workspace", workspaceName)
    )
    metrics.recordEvent(
      new Event()
        .withLabel("name", "gc_percent")
        .withLabel("value", gcPercent.toString)
        .withLabel("workspace", workspaceName)
    )
  }

  override def cancel(): Unit = {
    scheduled.cancel(false)
  }
}
