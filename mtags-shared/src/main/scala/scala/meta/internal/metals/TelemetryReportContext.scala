package scala.meta.internal.metals

import java.nio.file.Path
import java.util.Optional

import scala.meta.internal.metals.TelemetryReportContext._
import scala.meta.internal.metals.utils.TimestampedFile
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionOptionScala
import scala.meta.internal.telemetry

object TelemetryReportContext {
  case class Sanitizers(
      workspaceSanitizer: WorkspaceSanitizer,
      sourceCodeSanitizer: Option[SourceCodeSanitizer[_, _]]
  ) {
    def canSanitizeSources = sourceCodeSanitizer.isDefined
    def this(
        workspace: Option[Path],
        sourceCodeTransformer: Option[SourceCodeTransformer[_, _]]
    ) =
      this(
        workspaceSanitizer = new WorkspaceSanitizer(workspace),
        sourceCodeSanitizer =
          sourceCodeTransformer.map(new SourceCodeSanitizer(_))
      )
    val all: Seq[ReportSanitizer] =
      Seq(workspaceSanitizer) ++ sourceCodeSanitizer
  }
}

/**
 * A remote reporter sending reports to telemetry server aggregating the results. Operates in a best-effort manner. Created reporter does never reutrn any values.
 *
 * @param telemetryServerEndpoint
 * @param getReporterContext Constructor of reporter context metadata containg informations about user/server configuration of components
 */
class TelemetryReportContext(
    telemetryLevel: () => TelemetryLevel,
    reporterContext: () => telemetry.ReporterContext,
    sanitizers: Sanitizers,
    telemetryClientConfig: TelemetryClient.Config =
      TelemetryClient.Config.default,
    logger: LoggerAccess = LoggerAccess.system
) extends ReportContext {

  // Don't send reports with fragile user data - sources etc
  override lazy val unsanitized: Reporter = reporter("unsanitized")
  override lazy val incognito: Reporter = reporter("incognito")
  override lazy val bloop: Reporter = reporter("bloop")

  private val client = new TelemetryClient(
    config = telemetryClientConfig,
    telemetryLevel = telemetryLevel,
    logger = logger
  )

  private def reporter(name: String) = new TelemetryReporter(
    name = name,
    client = client,
    telemetryLevel = telemetryLevel,
    reporterContext = reporterContext,
    sanitizers = sanitizers,
    logger = logger
  )
}

private class TelemetryReporter(
    override val name: String,
    client: TelemetryClient,
    telemetryLevel: () => TelemetryLevel,
    reporterContext: () => telemetry.ReporterContext,
    sanitizers: TelemetryReportContext.Sanitizers,
    logger: LoggerAccess
) extends Reporter {

  override def getReports(): List[TimestampedFile] = Nil
  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    Nil
  override def deleteAll(): Unit = ()

  override def sanitize(message: String): String =
    sanitizers.all.foldRight(message)(_.apply(_))

  private def createSanitizedReport(report: Report) = new telemetry.ErrorReport(
    /* name =  */ report.name,
    /* text =  */ if (sanitizers.canSanitizeSources)
      Optional.of(sanitize(report.text))
    else Optional.empty(),
    /* id =  */ report.id.asJava,
    /* error =  */ report.error
      .map(telemetry.ExceptionSummary.fromThrowable(_, sanitize(_)))
      .asJava,
    /* reporterName =  */ name,
    /* reporterContext =  */ reporterContext() match {
      case ctx: telemetry.MetalsLspContext =>
        telemetry.ReporterContextUnion.metalsLSP(ctx)
      case ctx: telemetry.ScalaPresentationCompilerContext =>
        telemetry.ReporterContextUnion.scalaPresentationCompiler(ctx)
    }
  )

  override def create(
      unsanitizedReport: => Report,
      ifVerbose: Boolean
  ): Option[Path] = {
    if (telemetryLevel().reportErrors) {
      val report = createSanitizedReport(unsanitizedReport)
      if (report.getText().isPresent() || report.getError().isPresent())
        client.sendErrorReport(report)
      else
        logger.info(
          "Skiped reporting remotely unmeaningful report, no context or error, reportId=" +
            unsanitizedReport.id.getOrElse("null")
        )
    }
    None
  }
}
