package scala.meta.internal.telemetry

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros._

case class CrashReport(
    error: ExceptionSummary,
    componentName: String,
    env: Environment = Environment.instance,
    componentVersion: Option[String] = None,
    reporterContext: Option[ReporterContext] = None,
)

object CrashReport {
  implicit val codec: JsonValueCodec[CrashReport] = JsonCodecMaker.make
}

case class ErrorReport(
    name: String,
    reporterName: String,
    reporterContext: ReporterContext,
    env: Environment = Environment.instance,
    id: Option[String] = None,
    text: Option[String] = None,
    error: Option[ExceptionSummary] = None,
)

object ErrorReport {
  implicit val codec: JsonValueCodec[ErrorReport] = JsonCodecMaker.make
}
