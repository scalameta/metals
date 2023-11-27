package scala.meta.internal.metals

import scala.meta.internal.telemetry
import scala.meta.internal.metals.utils.TimestampedFile

import smithy4s.json.Json
import smithy4s.Schema
import requests.Response

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random

import java.io.StringWriter
import java.io.PrintWriter
import java.nio.file.Path

object RemoteTelemetryReportContext {
  def discoverTelemetryServer =
    sys.props.getOrElse("metals.telemetry-server", DefaultEndpoint)
  final val DefaultEndpoint =
    "https://scala3.westeurope.cloudapp.azure.com/telemetry"
}

/**
 * A remote reporter sending reports to telemetry server aggregating the results. Operates in a best-effort manner. Created reporter does never reutrn any values.
 *
 * @param telemetryServerEndpoint
 * @param getReporterContext Constructor of reporter context metadata containg informations about user/server configuration of components
 */
class RemoteTelemetryReportContext(
    serverEndpoint: String,
    workspace: Option[Path],
    getReporterContext: () => telemetry.ReporterContext
)(implicit ec: ExecutionContext)
    extends ReportContext {
  override lazy val unsanitized: Reporter = reporter("unsanitized")
  override lazy val incognito: Reporter = reporter("sanitized")
  override lazy val bloop: Reporter = reporter("bloop")

  private def reporter(name: String) = new TelemetryReporter(
    name,
    serverEndpoint,
    workspace,
    getReporterContext
  )
}

private class TelemetryReporter(
    override val name: String,
    serverEndpoint: String,
    workspace: Option[Path],
    getReporterContext: () => telemetry.ReporterContext
)(implicit ec: ExecutionContext)
    extends Reporter {

  override def getReports(): List[TimestampedFile] = Nil
  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    Nil
  override def deleteAll(): Unit = ()

  private val sanitizer = new ReportSanitizer(workspace)
  private lazy val environmentInfo = telemetry.Environment(
    java = telemetry.JavaInfo(
      version = sys.props("java.version"),
      distribution = sys.props.get("java.vendor")
    ),
    system = telemetry.SystemInfo(
      architecture = sys.props("os.arch"),
      name = sys.props("os.name"),
      version = sys.props("os.version")
    )
  )

  val client = new TelemetryClient(
    new TelemetryClient.Config(serverHost = serverEndpoint)
  )

  override def create(
      unsanitizedReport: => Report,
      ifVerbose: Boolean
  ): Option[Path] = {
    val report = sanitizer(unsanitizedReport)
    client
      .sendReportEvent(
        telemetry.ReportEvent(
          id = report.id,
          name = report.name,
          text = report.text,
          shortSummary = report.shortSummary,
          error = report.error.map(toReporterError),
          env = environmentInfo,
          reporterName = name,
          reporterContext = getReporterContext()
        )
      )
      .onComplete(println)
    None
  }

  private def toReporterError(e: Throwable) = telemetry.ReportedError(
    exceptions = {
      val exceptions = List.newBuilder[String]
      var current = e
      while (current != null) {
        exceptions += current.getClass().getName()
        current = current.getCause()
      }
      exceptions.result()
    },
    stacktrace = {
      val stringWriter = new StringWriter()
      scala.util.Using.resource(new PrintWriter(stringWriter)) {
        e.printStackTrace(_)
      }
      val stacktrace = stringWriter.toString()
      sanitizer(stacktrace)
    }
  )
}

private class TelemetryClient(config: TelemetryClient.Config)(implicit
    ec: ExecutionContext
) extends telemetry.TelemetryService[Future] {
  import TelemetryClient._
  import telemetry.{TelemetryServiceOperation => Op}

  implicit private def clientConfig: Config = config

  private val SendReportEvent = new Endpoint(Op.SendReportEvent)
  override def sendReportEvent(event: telemetry.ReportEvent): Future[Unit] =
    SendReportEvent(event).map(_ => ())
}

private object TelemetryClient {
  class DeserializationException(message: String)
      extends RuntimeException(message)

  case class Config(serverHost: String)

  private class Endpoint[-In, +Err, +Out](
      // format: off
      endpoint:  smithy4s.Endpoint[telemetry.TelemetryServiceOperation, In, Err, Out, _, _]
      // format: on
  )(implicit config: Config, ec: ExecutionContext) {
    def apply(data: In): Future[Either[Err, Out]] =
      execute(data).map(decodeResponse)

    private val httpEndpoint = endpoint.schema.hints
      .get[smithy.api.Http]
      .get

    private val endpointURL = s"${config.serverHost}${httpEndpoint.uri}"
    private val requester = requests.send(httpEndpoint.method.value)

    private def execute(
        data: In,
        retries: Int = 3,
        backoffMillis: Int = 100
    ): Future[Response] = {

      Future {
        requester(
          url = endpointURL,
          data = Json.writePrettyString(data)(endpoint.input),
          keepAlive = false,
          check = false
        )
      }.recoverWith {
        case _: requests.TimeoutException | _: requests.UnknownHostException
            if retries > 0 =>
          Thread.sleep(backoffMillis)
          execute(data, retries - 1, backoffMillis + Random.nextInt(1000))
      }
    }

    private def decodeResponse(response: Response): Either[Err, Out] = {
      def read[T: Schema] = Json
        .read[T](smithy4s.Blob(response.bytes))
        .left
        .map(err =>
          // Malformed content when reading, panic
          throw new DeserializationException(
            s"path=${err.path}, expeced=${err.expected}, message=${err.message}"
          )
        )

      if (response.is2xx) read[Out](endpoint.output)
      else
        endpoint.error match {
          case Some(errSchema) => read[Err](errSchema.schema).swap
          case _ =>
            throw new IllegalStateException(
              s"${httpEndpoint} should never result in error"
            )
        }
    }
  }
}
