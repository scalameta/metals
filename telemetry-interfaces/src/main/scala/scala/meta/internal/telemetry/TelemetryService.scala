package scala.meta.internal.telemetry

import com.github.plokhotnyuk.jsoniter_scala.core._
import scala.util.Try

class FireAndForgetEndpoint[In: JsonValueCodec](
    val method: String,
    val uri: String,
) {
  def encodeInput(request: In): String = writeToString(request)
  def decodeInput(request: String): Try[In] = Try { readFromString(request) }
}

// This will be migrated to tapir endpoints in the next Commit
object TelemetryService {
  val sendErrorReportEndpoint = new FireAndForgetEndpoint[ErrorReport](
    "POST",
    "/v1/telemetry/sendErrorReport",
  )
}

trait TelemetryService {
  def sendErrorReport(errorReport: ErrorReport): Unit
}
