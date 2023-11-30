package scala.meta.internal.metals

import scala.meta.internal.telemetry
import scala.meta.internal.metals.utils.TimestampedFile
import scala.meta.internal.jdk.OptionConverters._

import requests.Response
import com.google.gson.JsonSyntaxException

import scala.util.Random

import java.nio.file.Path
import java.io.InputStreamReader
import scala.util.Try
import RemoteReportContext.LoggerAccess
import java.util.Optional
object RemoteReportContext {
  def discoverTelemetryServer =
    sys.props.getOrElse("metals.telemetry-server", DefaultEndpoint)
  final val DefaultEndpoint = "http://localhost:8081"
  // "https://scala3.westeurope.cloudapp.azure.com/telemetry"

  case class Sanitizers(
      workspaceSanitizer: WorkspaceSanitizer,
      sourceCodeSanitizer: Option[SourceCodeSanitizer[_, _]]
  ) {
    def canSanitizeSources = sourceCodeSanitizer.isDefined
    def this(
        workspace: Option[Path],
        sourceCodeTransformer: Option[SourceCodeTransformer[_, _]]
    ) =
      this(
        workspaceSanitizer = new WorkspaceSanitizer(workspace),
        sourceCodeSanitizer =
          sourceCodeTransformer.map(new SourceCodeSanitizer(_))
      )
    val all = Seq(workspaceSanitizer) ++ sourceCodeSanitizer
  }

  // Proxy for different logging mechanism java.util.logging in PresentatilnCompiler and scribe in metals
  case class LoggerAccess(
      info: String => Unit,
      error: String => Unit,
      warning: String => Unit
  )
  object LoggerAccess {
    object system
        extends LoggerAccess(
          info = System.out.println(_),
          error = System.err.println(_),
          warning = System.err.println(_)
        )
  }
}

/**
 * A remote reporter sending reports to telemetry server aggregating the results. Operates in a best-effort manner. Created reporter does never reutrn any values.
 *
 * @param telemetryServerEndpoint
 * @param getReporterContext Constructor of reporter context metadata containg informations about user/server configuration of components
 */
class RemoteReportContext(
    serverEndpoint: String,
    getReporterContext: () => telemetry.ReporterContext,
    sanitizers: RemoteReportContext.Sanitizers,
    logger: LoggerAccess
) extends ReportContext {

  // Don't send reports with fragile user data - sources etc
  override lazy val unsanitized: Reporter = reporter("unsanitized")
  override lazy val incognito: Reporter = reporter("incognito")
  override lazy val bloop: Reporter = reporter("bloop")

  private def reporter(name: String) =
    new RemoteReporter(
      name = name,
      serverEndpoint = serverEndpoint,
      getReporterContext = getReporterContext,
      sanitizers = sanitizers,
      logger = logger
    )
}

private class RemoteReporter(
    override val name: String,
    serverEndpoint: String,
    getReporterContext: () => telemetry.ReporterContext,
    sanitizers: RemoteReportContext.Sanitizers,
    logger: LoggerAccess
) extends Reporter {

  override def getReports(): List[TimestampedFile] = Nil
  override def cleanUpOldReports(maxReportsNumber: Int): List[TimestampedFile] =
    Nil
  override def deleteAll(): Unit = ()

  private lazy val environmentInfo: telemetry.Environment =
    new telemetry.Environment(
      /* java = */ new telemetry.JavaInfo(
        /* version = */ sys.props("java.version"),
        /* distribution = */ sys.props.get("java.vendor").toJava
      ),
      /* system = */ new telemetry.SystemInfo(
        /* architecture = */ sys.props("os.arch"),
        /* name = */ sys.props("os.name"),
        /* version = */ sys.props("os.version")
      )
    )

  val client: telemetry.TelemetryService = new TelemetryClient(
    new TelemetryClient.Config(serverHost = serverEndpoint),
    logger = logger
  )

  override def sanitize(message: String): String =
    sanitizers.all.foldRight(message)(_.apply(_))

  private def createSanitizedReport(report: Report) = new telemetry.ReportEvent(
    /* name =  */ report.name,
    /* text =  */ if (sanitizers.canSanitizeSources)
      Optional.of(sanitize(report.text))
    else Optional.empty(),
    /* id =  */ report.id.toJava,
    /* error =  */ report.error
      .map(telemetry.ReportedError.fromThrowable(_, sanitize(_)))
      .toJava,
    /* reporterName =  */ name,
    /* reporterContext =  */ getReporterContext() match {
      case ctx: telemetry.MetalsLspContext =>
        telemetry.ReporterContextUnion.metalsLSP(ctx)
      case ctx: telemetry.ScalaPresentationCompilerContext =>
        telemetry.ReporterContextUnion.scalaPresentationCompiler(ctx)
      case ctx: telemetry.UnknownProducerContext =>
        telemetry.ReporterContextUnion.unknown(ctx)
    },
    /* env =  */ environmentInfo
  )

  override def create(
      unsanitizedReport: => Report,
      ifVerbose: Boolean
  ): Option[Path] = {
    val event = createSanitizedReport(unsanitizedReport)
    if (event.getText().isPresent() || event.getError().isPresent())
      client.sendReportEvent(event)
    else
      logger.info(
        "Skiped reporting remotely unmeaningful report, no context or error, reportId=" +
          unsanitizedReport.id.getOrElse("null")
      )
    None
  }
}

private class TelemetryClient(
    config: TelemetryClient.Config,
    logger: LoggerAccess
) extends telemetry.TelemetryService {
  import telemetry.{TelemetryService => api}
  import TelemetryClient._

  implicit private def clientConfig: Config = config

  private val SendReportEvent = new Endpoint(api.SendReportEventEndpoint)

  override def sendReportEvent(event: telemetry.ReportEvent): Unit =
    SendReportEvent(event).recover { case err =>
      logger.warning(s"Failed to send report: ${err}")
    }
}

private object TelemetryClient {
  class DeserializationException(message: String)
      extends RuntimeException(message)

  case class Config(serverHost: String)

  class Endpoint[-In, +Out](
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
