package tests

import java.util.Optional

import scala.collection.mutable

import scala.meta.infra.Event
import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
import scala.meta.infra.Metric
import scala.meta.infra.MonitoringClient
import scala.meta.internal.metals.Configs.TelemetryConfig
import scala.meta.internal.metals.{BuildInfo => V}

object TestingInfra {
  val metrics: mutable.ArrayBuffer[Metric] = mutable.ArrayBuffer[Metric]()
  val events: mutable.ArrayBuffer[Event] = mutable.ArrayBuffer[Event]()
  val testFlags: mutable.ArrayBuffer[FeatureFlag] =
    mutable.ArrayBuffer[FeatureFlag]()
}
class TestingMonitoringClient extends MonitoringClient {
  override def recordUsage(metric: Metric): Unit =
    TestingInfra.metrics.append(metric)
  override def recordEvent(event: Event): Unit =
    TestingInfra.events.append(event)
  override def shutdown(): Unit = {}
}

class TestingFeatureFlagProvider extends FeatureFlagProvider {
  override def readBoolean(flag: FeatureFlag): Optional[java.lang.Boolean] = {
    TestingInfra.testFlags.append(flag)
    Optional.empty()
  }
}

class InfraSuite extends BaseLspSuite("infraSuite") {
  override def telemetryConfig: TelemetryConfig = new TelemetryConfig("enabled")
  test("dependency".tag(FlakyWindows)) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.scala213}",
           |    "libraryDependencies" : [ "org.scala-lang:scala-library:${V.scala213}"]
           |  }
           |}
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, world!")
           |  }
           |}
           |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assert(TestingInfra.events.nonEmpty)
      _ = assert(TestingInfra.metrics.isEmpty) // May get removed
      _ = assert(TestingInfra.testFlags.nonEmpty)
    } yield ()
  }

}
