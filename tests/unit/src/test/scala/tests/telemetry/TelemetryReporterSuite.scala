package tests.telemetry

import scala.collection.mutable

import scala.meta.internal.metals
import scala.meta.internal.pc.StandardReport
import scala.meta.internal.telemetry._
import scala.meta.pc.Report

import tests.BaseSuite
import tests.telemetry.SampleReports

class TelemetryReporterSuite extends BaseSuite {
  def simpleReport(id: String): Report = StandardReport(
    name = "name",
    text = "text",
    shortSummary = "summmary",
    path = None,
    id = Some(id),
    error = Some(new RuntimeException("A", new NullPointerException())),
  )

  // Ensure that tests by default don't use telemetry reporting, it should be disabled in the build.sbt
  test("default telemetry level") {
    def getDefault = metals.TelemetryLevel.default
    assertEquals(metals.TelemetryLevel.Off, getDefault)
  }

  def testCase(level: metals.TelemetryLevel, expected: Set[String]): Unit =
    test(
      s"Telemetry level: ${level} sends telemetry for ${expected.mkString("(", ", ", ")")}"
    ) {
      val client = new TestTelemetryClient()
      val reportContexts = Seq(
        SampleReports.metalsLspReport(),
        SampleReports.scalaPresentationCompilerReport(),
      ).map(_.reporterContext)

      for {
        reporterCtx <- reportContexts
        reporter = new TelemetryReportContext(
          telemetryLevel = () => level,
          reporterContext = () => reporterCtx,
          workspaceSanitizer = new metals.WorkspaceSanitizer(None),
          telemetryClient = client,
        )
      } {

        reporter.incognito.create(simpleReport("incognito"))
        reporter.bloop.create(simpleReport("bloop"))
        reporter.unsanitized.create(simpleReport("unsanitized"))

        val received = client.reportsBuffer.map(_.id.get).toSet
        assertEquals(received, expected)
      }
    }

  testCase(metals.TelemetryLevel.Off, Set())
  testCase(metals.TelemetryLevel.Anonymous, Set("incognito", "bloop"))
  testCase(metals.TelemetryLevel.Full, Set("incognito", "bloop", "unsanitized"))
}

class TestTelemetryClient extends TelemetryClient {
  val reportsBuffer = mutable.ListBuffer.empty[ErrorReport]

  override val sendReport: ErrorReport => Unit = error => {
    reportsBuffer += error
  }
}
