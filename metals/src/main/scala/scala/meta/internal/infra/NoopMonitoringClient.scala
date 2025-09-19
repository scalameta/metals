package scala.meta.internal.infra

import scala.meta.infra.Event
import scala.meta.infra.Metric
import scala.meta.infra.MonitoringClient

class NoopMonitoringClient extends MonitoringClient {
  override def recordUsage(metric: Metric): Unit = {}
  override def recordEvent(event: Event): Unit = {}
  override def shutdown(): Unit = {}
}
