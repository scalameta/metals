package scala.meta.internal.infra

import java.util.ServiceLoader

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.infra.Event
import scala.meta.infra.Metric
import scala.meta.infra.MonitoringClient
import scala.meta.internal.jdk.CollectionConverters._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AggregateMonitoringClient(val underlying: List[MonitoringClient])
    extends MonitoringClient {

  val logger: Logger =
    LoggerFactory.getLogger(classOf[AggregateMonitoringClient])

  @tailrec
  private def foreach(
      c: List[MonitoringClient],
      fn: MonitoringClient => Unit,
  ): Unit = {
    c match {
      case client :: tail =>
        try fn(client)
        catch {
          case ex if NonFatal(ex) =>
            logger.error(s"Error while calling $fn on $client", ex)
        }
        foreach(tail, fn)
      case Nil => ()
    }
  }
  override def recordUsage(metric: Metric): Unit =
    foreach(underlying, _.recordUsage(metric))

  override def recordEvent(event: Event): Unit =
    foreach(underlying, _.recordEvent(event))
  override def shutdown(): Unit =
    foreach(underlying, _.shutdown())
}

object AggregateMonitoringClient {
  def fromServiceLoader(): MonitoringClient =
    new AggregateMonitoringClient(
      ServiceLoader.load(classOf[MonitoringClient]).iterator.asScala.toList
    )
}
