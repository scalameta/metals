package scala.meta.internal.telemetry

import scala.meta.internal.metals.TelemetryLevel

import com.google.common.util.concurrent.RateLimiter
import sttp.client3._
import sttp.model.Uri
import sttp.tapir.client.sttp.SttpClientInterpreter

object TelemetryClient {

  case class Config(serverHost: Uri)
  object Config {
    // private final val DefaultTelemetryEndpoint =
    //   "https://scala3.westeurope.cloudapp.azure.com/telemetry"
    private final val DefaultTelemetryEndpoint = uri"http://localhost:8081"
    val default: Config = Config(DefaultTelemetryEndpoint)
  }

  protected[telemetry] val interpreter: SttpClientInterpreter =
    SttpClientInterpreter()

}

trait TelemetryClient {
  val telemetryLevel: TelemetryLevel

  protected val sendErrorReportImpl: ErrorReport => Unit
  protected val sendCrashReportImpl: CrashReport => Unit

  val sendErrorReport: ErrorReport => Unit = report =>
    if (telemetryLevel.sendErrors) {
      scribe.debug("Sending remote error report.")
      sendErrorReportImpl(report)
    }

  val sendCrashReport: CrashReport => Unit = report => {
    if (telemetryLevel.sendCrashes) {
      scribe.debug("Sending remote crash report.")
      sendCrashReportImpl(report)
    }
  }

}

class TelemetryClientImpl(
    val telemetryLevel: TelemetryLevel,
    config: TelemetryClient.Config = TelemetryClient.Config.default,
) extends TelemetryClient {
  import TelemetryClient._
  private val backend = HttpClientFutureBackend()
  private val rateLimiter = RateLimiter.create(1.0 / 5.0)

  protected val sendErrorReportImpl: ErrorReport => Unit = report =>
    if (rateLimiter.tryAcquire()) {
      interpreter
        .toClient(
          TelemetryEndpoints.sendErrorReport,
          baseUri = Some(config.serverHost),
          backend = backend,
        )
        .apply(report)
    } else scribe.debug("Report not send because of quota.")

  protected val sendCrashReportImpl: CrashReport => Unit = report =>
    interpreter
      .toClient(
        TelemetryEndpoints.sendCrashReport,
        baseUri = Some(config.serverHost),
        backend = backend,
      )
      .apply(report)

}
