package scala.meta.internal.builds

import scala.meta.internal.bsp.BspSession
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.Tables

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

  private def createReport(message: String) =
    reportContext.bloop.create(
      Report(message.trimTo(20), message, shortSummary = message.trimTo(100))
    )
}
