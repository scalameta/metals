package scala.meta.internal.metals

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.CompilerThrowable
import scala.meta.pc.VirtualFileParams

case class PcQueryContext(
    params: Option[VirtualFileParams],
    additionalReportingData: () => String
)(implicit rc: ReportContext) {
  def report(name: String, e: Throwable, additionalInfo: String): Unit = {
    val error = CompilerThrowable.trimStackTrace(e)
    val report =
      Report(
        name,
        s"""|occurred in the presentation compiler.
            |
            |$additionalInfo
            |
            |action parameters:
            |${params.map(_.printed()).getOrElse("<NONE>")}
            |
            |presentation compiler configuration:
            |${additionalReportingData()}
            |
            |""".stripMargin,
        error,
        path = params.map(_.uri())
      )
    rc.unsanitized.create(report)
  }
}
