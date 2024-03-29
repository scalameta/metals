package scala.meta.internal.telemetry

import sttp.client3._

import scala.meta.internal.metals.LoggerAccess

import sttp.tapir.client.sttp.SttpClientInterpreter
import sttp.model.Uri
import com.google.common.util.concurrent.RateLimiter

object TelemetryClient {

  case class Config(serverHost: Uri)
  object Config {
    // private final val DefaultTelemetryEndpoint =
    //   "https://scala3.westeurope.cloudapp.azure.com/telemetry"
    private final val DefaultTelemetryEndpoint = uri"http://localhost:8081"
    val default: Config = Config(DefaultTelemetryEndpoint)
  }

  private val interpreter = SttpClientInterpreter()

}

class TelemetryClient(
    config: TelemetryClient.Config = TelemetryClient.Config.default,
    logger: LoggerAccess = LoggerAccess.system,
) {
  import TelemetryClient._
  val rateLimiter = RateLimiter.create(1.0 / 5.0)

  val backend = HttpClientFutureBackend()
  val sendReport: ErrorReport => Unit = report => {
    if (rateLimiter.tryAcquire()) {
      logger.debug("Sending telemetry report.")
      interpreter
        .toClient(
          TelemetryEndpoints.sendReport,
          baseUri = Some(config.serverHost),
          backend = backend,
        )
        .apply(report)
    } else logger.debug("Report was omitted, because of quota")
    ()
  }
}
