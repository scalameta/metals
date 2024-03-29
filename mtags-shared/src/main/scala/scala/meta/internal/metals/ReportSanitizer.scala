package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionOptionalJava
import scala.meta.internal.pc.StandardReport
import scala.meta.pc.Report

trait ReportSanitizer {
  final def apply(report: Report): Report = {
    val sanitizedPath: Option[String] = report.path.asScala.map(sanitize(_))
    val sanitizedId: Option[String] = report.id.asScala.map(sanitize(_))
    StandardReport(
      name = sanitize(report.name),
      text = sanitize(report.text),
      shortSummary = sanitize(report.shortSummary),
      path = sanitizedPath,
      id = sanitizedId
    )
  }

  final def apply(text: String): String = sanitize(text)

  def sanitize(text: String): String
}

class WorkspaceSanitizer(workspace: Option[Path]) extends ReportSanitizer {
  private lazy val userHome = Option(System.getProperty("user.home"))

  override def sanitize(text: String): String = {
    val textAfterWokspaceReplace = workspace
      .map(_.toString())
      .foldLeft(text)(
        _.replace(_, StdReportContext.WORKSPACE_STR)
      )
    userHome.foldLeft(textAfterWokspaceReplace)(
      _.replace(_, StdReportContext.HOME_STR)
    )
  }
}
