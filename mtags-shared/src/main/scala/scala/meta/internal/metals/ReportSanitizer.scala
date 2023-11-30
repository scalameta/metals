package scala.meta.internal.metals

import java.nio.file.Path

trait ReportSanitizer {
  final def apply(report: Report): Report = report.copy(
    id = report.id.map(sanitize),
    name = sanitize(report.name),
    text = sanitize(report.text),
    shortSummary = sanitize(report.shortSummary),
    path = report.path.map(sanitize)
  )
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
