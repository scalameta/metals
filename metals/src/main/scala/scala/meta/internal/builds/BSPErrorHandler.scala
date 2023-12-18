package scala.meta.internal.builds

import java.security.MessageDigest

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Tables

import com.google.common.io.BaseEncoding

class BspErrorHandler(
    currentSession: () => Option[BspSession],
    tables: Tables,
    bspStatus: ConnectionBspStatus,
)(implicit reportContext: ReportContext) {
  def onError(message: String): Unit = {
    if (shouldShowBspError) {
      for {
        report <- createReport(message)
        if !tables.dismissedNotifications.BspErrors.isDismissed
      } bspStatus.showError(message, report)
    } else logError(message)
  }

  def shouldShowBspError: Boolean = currentSession().exists(session =>
    session.main.isBloop || session.main.isScalaCLI
  )

  protected def logError(message: String): Unit = scribe.error(message)

  private def createReport(message: String) = {
    val digest = MessageDigest.getInstance("MD5").digest(message.getBytes)
    val id = BaseEncoding.base64().encode(digest)
    val sanitized = reportContext.bloop.sanitize(message)
    reportContext.bloop.create(
      Report(
        sanitized.trimTo(20),
        s"""|### Bloop error:
            |
            |$message""".stripMargin,
        shortSummary = sanitized.trimTo(100),
        path = None,
        id = Some(id),
      )
    )
  }
}
