package scala.meta.internal.telemetry

import java.io.InputStreamReader

import scala.util.Random
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.LoggerAccess
import scala.meta.internal.metals.TelemetryLevel
import scala.meta.internal.telemetry

import com.google.gson.JsonSyntaxException
import requests.Response

object TelemetryClient {
  private class DeserializationException(message: String)
      extends RuntimeException(message)

  case class Config(serverHost: String)
  object Config {
    // private final val DefaultTelemetryEndpoint =
    //   "https://scala3.westeurope.cloudapp.azure.com/telemetry"
    private final val DefaultTelemetryEndpoint =
      "http://localhost:8081"

    val default: Config = Config(DefaultTelemetryEndpoint)
  }

  private class Endpoint[-In, +Out](
      endpoint: telemetry.ServiceEndpoint[In, Out]
  )(implicit config: Config) {
    def apply(data: In): Try[Out] = {
      val json = encodeRequest(data)
      execute(json).map(decodeResponse)
    }

    private val endpointURL = s"${config.serverHost}${endpoint.getUri()}"
    private val requester = requests.send(endpoint.getMethod())

    private def execute(
        data: String,
        retries: Int = 3,
        backoffMillis: Int = 100,
    ): Try[Response] = Try {
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

    private def encodeRequest(request: In): String =
      telemetry.GsonCodecs.gson.toJson(request)

    private def decodeResponse(response: Response): Out = {
      if (response.is2xx) {
        val outputType = endpoint.getOutputType()
        if (outputType == classOf[Void]) ().asInstanceOf[Out]
        else {
          response.readBytesThrough { is =>
            try
              telemetry.GsonCodecs.gson
                .fromJson(new InputStreamReader(is), outputType)
            catch {
              case err: JsonSyntaxException =>
                throw new DeserializationException(err.getMessage())
            }
          }
        }
      } else
        throw new IllegalStateException(
          s"${endpoint.getMethod()}:${endpoint.getUri()} should never result in error, got ${response}"
        )
    }
  }
}

private[meta] class TelemetryClient(
    telemetryLevel: () => TelemetryLevel,
    config: TelemetryClient.Config = TelemetryClient.Config.default,
    logger: LoggerAccess = LoggerAccess.system,
) extends telemetry.TelemetryService {
  import telemetry.{TelemetryService => api}
  import TelemetryClient._

  implicit private def clientConfig: Config = config

  private val SendErrorReport = new Endpoint(api.SendErrorReportEndpoint)
  private val SendCrashReport = new Endpoint(api.SendCrashReportEndpoint)

  override def sendErrorReport(report: telemetry.ErrorReport): Unit =
    if (telemetryLevel().reportErrors) {
      SendErrorReport(report)
        .recover { case NonFatal(err) =>
          logSendFailure(reportType = "error")(err)
        }
    }

  override def sendCrashReport(report: telemetry.CrashReport): Unit =
    if (telemetryLevel().reportCrash) {
      SendCrashReport(report)
        .recover { case NonFatal(err) =>
          logSendFailure(reportType = "crash")(err)
        }
    }

  private def logSendFailure(reportType: String)(error: Throwable) =
    logger.debug(s"Failed to send $reportType report: ${error}")

}
