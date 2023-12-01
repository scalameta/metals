package scala.meta.internal.metals

import scala.meta.internal.telemetry

import scala.util.Random
import scala.util.Try

import java.io.InputStreamReader

import com.google.gson.JsonSyntaxException
import requests.Response

object TelemetryClient {
  private class DeserializationException(message: String)
      extends RuntimeException(message)

  case class Config(serverHost: String)
  object Config {
    private def discoverTelemetryServer =
      sys.props.getOrElse("metals.telemetry-server", DefaultEndpoint)
    final val DefaultEndpoint =
      "https://scala3.westeurope.cloudapp.azure.com/telemetry"

    val default = Config(discoverTelemetryServer)
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
        backoffMillis: Int = 100
    ): Try[Response] = Try {
      requester(
        url = endpointURL,
        data = data,
        keepAlive = false,
        check = false
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

private class TelemetryClient(
    telemetryLevel: () => TelemetryLevel,
    config: TelemetryClient.Config = TelemetryClient.Config.default,
    logger: LoggerAccess = LoggerAccess.system
) extends telemetry.TelemetryService {
  import telemetry.{TelemetryService => api}
  import TelemetryClient._

  implicit private def clientConfig: Config = config

  private val SendReportEvent = new Endpoint(api.SendReportEventEndpoint)

  override def sendReportEvent(event: telemetry.ReportEvent): Unit =
    if (telemetryLevel().reportErrors) {
      SendReportEvent(event).recover { case err =>
        logger.warning(s"Failed to send report: ${err}")
      }
    }
}
