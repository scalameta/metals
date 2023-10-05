package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.internal.metals.utils.TimestampedFile

object LoggerReporter extends Reporter {

  override def name: String = "logger-report"
  override def create(report: => Report, ifVerbose: Boolean): Option[Path] = {
    scribe.info(
      s"Report ${report.name}: ${report.fullText(withIdAndSummary = false)}"
    )
    None
  }

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
