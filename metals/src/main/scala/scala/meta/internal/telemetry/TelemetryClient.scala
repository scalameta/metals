package scala.meta.internal.telemetry

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random
import scala.util.Success

import scala.meta.internal.metals.LoggerAccess
import scala.meta.internal.metals.TelemetryLevel
import scala.meta.internal.telemetry

import requests.Response

object TelemetryClient {

  case class Config(serverHost: String)
  object Config {
    // private final val DefaultTelemetryEndpoint =
    //   "https://scala3.westeurope.cloudapp.azure.com/telemetry"
    private final val DefaultTelemetryEndpoint = "http://localhost:8081"
    val default: Config = Config(DefaultTelemetryEndpoint)
  }

  private class TelemetryRequest[In](
      endpoint: telemetry.FireAndForgetEndpoint[In],
      logger: LoggerAccess,
  )(implicit config: Config, ec: ExecutionContext) {
    private val endpointURL = s"${config.serverHost}${endpoint.uri}"
    private val requester = requests.send(endpoint.method)
    println(s"TelemetryClient: sending $endpointURL")

    def apply(data: In): Unit = {
      val json = endpoint.encodeInput(data)
      val response = execute(json)
      acknowledgeResponse(response)
    }

    private def execute(
        data: String,
        retries: Int = 3,
        backoffMillis: Int = 100,
    ): Future[Response] = Future {
      requester(
        url = endpointURL,
        data = data,
        keepAlive = false,
        check = false,
      )
    }.recoverWith {
      case _: requests.TimeoutException | _: requests.UnknownHostException
          if retries > 0 =>
        Thread.sleep(backoffMillis)
        execute(data, retries - 1, backoffMillis + Random.nextInt(1000))
    }

    private def acknowledgeResponse(response: Future[Response]): Unit =
      response.onComplete {
        case Success(value) if value.is2xx =>
        case _ =>
          logger.debug(
            s"${endpoint.method}:${endpoint.uri} should never result in error, got ${response}"
          )
      }
  }
}

private[meta] class TelemetryClient(
    telemetryLevel: () => TelemetryLevel,
    config: TelemetryClient.Config = TelemetryClient.Config.default,
    logger: LoggerAccess = LoggerAccess.system,
)(implicit ec: ExecutionContext)
    extends telemetry.TelemetryService {
  import TelemetryClient._
  import telemetry.TelemetryService._

  implicit private def clientConfig: Config = config

  private val sendErrorReport0 =
    new TelemetryRequest(sendErrorReportEndpoint, logger)
  private val sendCrashReport0 =
    new TelemetryRequest(sendCrashReportEndpoint, logger)

  def sendErrorReport(report: telemetry.ErrorReport): Unit =
    if (telemetryLevel().enabled) sendErrorReport0(report)

  def sendCrashReport(report: telemetry.CrashReport): Unit =
    if (telemetryLevel().enabled) sendCrashReport0(report)

}
