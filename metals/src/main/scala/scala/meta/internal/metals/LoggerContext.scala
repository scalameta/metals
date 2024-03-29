package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

import scala.meta.pc.Report
import scala.meta.pc.ReportContext
import scala.meta.pc.Reporter
import scala.meta.pc.TimestampedFile

object LoggerReporter extends Reporter {

  override def name: String = "logger-report"
  override def create(report: Report, ifVerbose: Boolean): ju.Optional[Path] = {
    scribe.info(
      s"Report ${report.name}: ${report.fullText(false)}"
    )
    ju.Optional.empty()
  }

  override def cleanUpOldReports(
      maxReportsNumber: Int
  ): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def getReports(): ju.List[TimestampedFile] =
    ju.Collections.emptyList()

  override def deleteAll(): Unit = {}
}

object LoggerReportContext extends ReportContext {

  override def unsanitized: Reporter = LoggerReporter

  override def incognito: Reporter = LoggerReporter

  override def bloop: Reporter = LoggerReporter
}
