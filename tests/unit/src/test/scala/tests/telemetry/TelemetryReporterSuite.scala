package tests.telemetry

import scala.collection.mutable

import scala.meta.internal.metals._
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

  def testSendError(
      configuration: TelemetryConfiguration,
      expected: Set[String],
  ): Unit =
    test(
      s"Telemetry configuration: ${configuration} sends errors for ${expected
          .mkString("(", ", ", ")")}"
    ) {
      val client = new TestTelemetryClient(configuration.telemetryLevel)
      val reportContexts = Seq(
        SampleReports.metalsLspReport(),
        SampleReports.scalaPresentationCompilerReport(),
      ).map(_.reporterContext)

      for {
        reporterCtx <- reportContexts
        reporter = new TelemetryReportContext(
          telemetryConfiguration = () => configuration,
          reporterContext = () => reporterCtx,
          workspaceSanitizer = new WorkspaceSanitizer(None),
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

  def testSendCrash(
      telemetryLevel: TelemetryLevel,
      expected: Set[String],
  ): Unit =
    test(
      s"Telemetry configuration: ${telemetryLevel} sends crashes for ${expected
          .mkString("(", ", ", ")")}"
    ) {
      val client = new TestTelemetryClient(telemetryLevel)
      client.sendCrashReport(
        CrashReport(ExceptionSummary("message", Nil), "testCrash")
      )

      val received = client.crashBuffer.map(_.componentName).toSet
      assertEquals(received, expected)
    }

  testSendError(TelemetryConfiguration(TelemetryLevel.Off, false), Set())
  testSendError(TelemetryConfiguration(TelemetryLevel.Off, true), Set())

  testSendError(TelemetryConfiguration(TelemetryLevel.Crash, false), Set())
  testSendError(TelemetryConfiguration(TelemetryLevel.Crash, true), Set())

  testSendError(
    TelemetryConfiguration(TelemetryLevel.Error, false),
    Set("incognito", "bloop"),
  )
  testSendError(
    TelemetryConfiguration(TelemetryLevel.Error, true),
    Set("incognito", "bloop", "unsanitized"),
  )

  testSendError(
    TelemetryConfiguration(TelemetryLevel.All, false),
    Set("incognito", "bloop"),
  )
  testSendError(
    TelemetryConfiguration(TelemetryLevel.All, true),
    Set("incognito", "bloop", "unsanitized"),
  )

  testSendCrash(TelemetryLevel.Off, Set())
  testSendCrash(TelemetryLevel.Crash, Set("testCrash"))
  testSendCrash(TelemetryLevel.Error, Set("testCrash"))
  testSendCrash(TelemetryLevel.All, Set("testCrash"))

}

class TestTelemetryClient(val telemetryLevel: TelemetryLevel)
    extends TelemetryClient {
  val reportsBuffer = mutable.ListBuffer.empty[ErrorReport]
  val crashBuffer = mutable.ListBuffer.empty[CrashReport]

  override protected val sendErrorReportImpl: ErrorReport => Unit = error =>
    reportsBuffer += error

  override protected val sendCrashReportImpl: CrashReport => Unit = crash =>
    crashBuffer += crash
}
