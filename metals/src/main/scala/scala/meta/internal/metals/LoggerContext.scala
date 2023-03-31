package scala.meta.internal.metals

import scala.meta.io.AbsolutePath

object LoggerReporter extends Reporter {

  override def createReport(
      name: String,
      text: String,
  ): Option[AbsolutePath] = {
    scribe.info(s"Report $name: $text")
    None
  }

  override def createReport(
      name: String,
      text: String,
      e: Throwable,
  ): Option[AbsolutePath] = {
    scribe.info(s"""|Report $name: $text
                    |Error: ${e.getMessage()}""".stripMargin)
    None
  }

  override def cleanUpOldReports(maxReportsNumber: Int): List[Report] = List()

  override def getReports(): List[Report] = List()

  override def deleteAll(): Unit = {}
}

object LoggerReportContext extends ReportContext {

  override def unsanitized: Reporter = LoggerReporter

  override def incognito: Reporter = LoggerReporter

  override def bloop: Reporter = LoggerReporter
}
