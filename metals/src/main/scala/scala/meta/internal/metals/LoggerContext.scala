package scala.meta.internal.metals

import java.lang
import java.nio.file.Path
import java.util.Optional

import scala.meta.internal.metals.utils.TimestampedFile
import scala.meta.pc.reports.LazyReport

object LoggerReporter extends Reporter {

  override def create(
      lazyReport: LazyReport,
      ifVerbose: lang.Boolean,
  ): Optional[Path] = {
    val report = lazyReport.create()
    scribe.info(
      s"Report ${report.name}: ${report.fullText( /* withIdAndSummary = */ false)}"
    )
    Optional.empty()
  }

  override def name: String = "logger-report"

  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    List()

  override def getReports(): List[TimestampedFile] = List()

  override def deleteAll(): Unit = {}
}

object LoggerReportContext extends ReportContext {

  override def unsanitized: Reporter = LoggerReporter

  override def incognito: Reporter = LoggerReporter

  override def bloop: Reporter = LoggerReporter
}
