package scala.meta.internal.infra;

import java.util.concurrent.Executor

import scala.meta.infra.Event
import scala.meta.infra.Metric
import scala.meta.infra.MonitoringClient

/**
 * Wraps a MonitoringClient to ensure that all method invocations are off the
 * blocking thread.
 */
final class NonBlockingMonitoringClient(
    delegate: MonitoringClient,
    executor: Executor,
) extends MonitoringClient {

  override def recordUsage(metric: Metric): Unit = {
    executor.execute(() => delegate.recordUsage(metric));
  }

  override def recordEvent(event: Event): Unit = {
    executor.execute(() => delegate.recordEvent(event));
  }

  override def shutdown(): Unit = {
    executor.execute(() => delegate.shutdown());
  }

}
