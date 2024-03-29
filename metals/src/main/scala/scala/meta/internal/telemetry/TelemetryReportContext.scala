package scala.meta.internal.telemetry

import java.nio.file.Path
import java.{util => ju}

import scala.meta.internal.metals.LoggerAccess
import scala.meta.internal.metals.TelemetryLevel
import scala.meta.internal.metals.WorkspaceSanitizer
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionOptionalJava
import scala.meta.internal.telemetry
import scala.meta.pc.Report
import scala.meta.pc.ReportContext
import scala.meta.pc.Reporter
import scala.meta.pc.TimestampedFile
import scala.meta.internal.metals.EmptyReporter
import com.google.common.collect.EvictingQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * A remote reporter sending reports to telemetry server aggregating the results. Operates in a best-effort manner. Created reporter does never reutrn any values.
 *
 * @param telemetryServerEndpoint
 * @param getReporterContext Constructor of reporter context metadata containg informations about user/server configuration of components
 */
class TelemetryReportContext(
    telemetryLevel: () => TelemetryLevel,
    reporterContext: () => telemetry.ReporterContext,
    workspaceSanitizer: WorkspaceSanitizer,
    telemetryClient: TelemetryClient,
    logger: LoggerAccess = LoggerAccess.system,
) extends ReportContext {

  val telemetryLevel0 = telemetryLevel()

  // Don't send reports with fragile user data - sources etc
  override lazy val unsanitized: Reporter =
    if (telemetryLevel0 == TelemetryLevel.Full) reporter("unsanitized")
    else EmptyReporter
  override lazy val incognito: Reporter =
    if (telemetryLevel0.enabled) reporter("incognito") else EmptyReporter
  override lazy val bloop: Reporter =
    if (telemetryLevel0.enabled) reporter("bloop") else EmptyReporter

  private def reporter(name: String) = new TelemetryReporter(
    name = name,
    client = telemetryClient,
    reporterContext = reporterContext,
    sanitizers = workspaceSanitizer,
    logger = logger,
  )
}

private class TelemetryReporter(
    override val name: String,
    client: TelemetryClient,
    reporterContext: () => telemetry.ReporterContext,
    sanitizers: WorkspaceSanitizer,
    logger: LoggerAccess,
) extends Reporter {

  val previousTraces: AtomicReference[EvictingQueue[ExceptionSummary]] =
    new AtomicReference(EvictingQueue.create(10))

  def alreadyReported(report: ErrorReport): Boolean = {
    report.error.exists(previousTraces.get.contains)
  }

  override def getReports(): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def cleanUpOldReports(
      maxReportsNumber: Int
  ): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def deleteAll(): Unit = ()

  override def sanitize(message: String): String =
    sanitizers(message)

  private def createSanitizedReport(report: Report) = {
    new telemetry.ErrorReport(
      name = report.name,
      reporterName = name,
      reporterContext = reporterContext(),
      id = report.id.asScala,
      text = report.text,
      error = report.error
        .map(telemetry.ExceptionSummary.from(_, sanitize(_)))
        .asScala,
    )
  }

  override def create(
      unsanitizedReport: Report,
      ifVerbose: Boolean,
  ): ju.Optional[Path] = {
    val report = createSanitizedReport(unsanitizedReport)
    if (!alreadyReported(report)) {
      report.error.foreach(a => previousTraces.get.add(a))
      client.sendReport(report)
    } else {
      logger.debug(
        "Skipped reporting remotely duplicated report, reportId=" +
          unsanitizedReport.id.orElse("null")
      )
    }
    ju.Optional.empty()
  }
}
