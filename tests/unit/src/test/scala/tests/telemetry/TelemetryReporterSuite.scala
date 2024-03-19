package tests.telemetry

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Optional

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals
import scala.meta.internal.mtags.CommonMtagsEnrichments.XtensionOptionalJava
import scala.meta.internal.pc.StandardReport
import scala.meta.internal.telemetry._
import scala.meta.pc.Report

import io.undertow.server.handlers.BlockingHandler
import io.undertow.server.handlers.PathHandler
import tests.BaseSuite
import tests.telemetry.SampleReports

class TelemetryReporterSuite extends BaseSuite {
  def simpleReport(id: String): Report = StandardReport(
    name = "name",
    text = "text",
    shortSummary = "sumamry",
    path = None,
    id = Some(id),
    error = Some(new RuntimeException("A", new NullPointerException())),
  )

  // Ensure that tests by default don't use telemetry reporting, it should be disabled in the build.sbt
  test("default telemetry level") {
    def getDefault = metals.TelemetryLevel.default
    assertEquals(metals.TelemetryLevel.Off, getDefault)
  }

  // Remote telemetry reporter should be treated as best effort, ensure that logging
  test("ignore connectiviy failures") {
    val reporter = new TelemetryReportContext(
      telemetryClientConfig = TelemetryClient.Config.default.copy(serverHost =
        "https://not.existing.endpoint.for.metals.tests:8081"
      ),
      telemetryLevel = () => metals.TelemetryLevel.Full,
      reporterContext = () => SampleReports.metalsLspReport().reporterContext,
      sanitizers = new TelemetryReportContext.Sanitizers(None, None),
    )

    assertEquals(
      Optional.empty[Path](),
      reporter.incognito.create(simpleReport("")),
    )
  }

  // Test end-to-end connection and event serialization using local http server implementing TelemetryService endpoints
  test("connect with local server") {
    implicit val ctx = new MockTelemetryServer.Context()
    val server = MockTelemetryServer("127.0.0.1", 8081)
    server.start()
    try {
      val serverEndpoint = MockTelemetryServer.address(server)
      for {
        reporterCtx <- Seq(
          SampleReports.metalsLspReport(),
          SampleReports.scalaPresentationCompilerReport(),
        ).map(_.reporterContext)
        reporter = new TelemetryReportContext(
          telemetryClientConfig = TelemetryClient.Config.default
            .copy(serverHost = serverEndpoint),
          telemetryLevel = () => metals.TelemetryLevel.Full,
          reporterContext = () => reporterCtx,
          sanitizers = new TelemetryReportContext.Sanitizers(
            None,
            Some(metals.ScalametaSourceCodeTransformer),
          ),
        )
      } {
        val createdReport = simpleReport(reporterCtx.toString())
        reporter.incognito.create(createdReport)
        Thread.sleep(5000) // wait for the server to receive the event
        val received = ctx.errors.filter(_.id == createdReport.id.asScala)
        assert(received.nonEmpty, "Not received matching id")
        assert(received.size == 1, "Found more then 1 received event")
      }
    } finally server.stop()
  }
}

object MockTelemetryServer {
  import io.undertow.Handlers.path
  import io.undertow.Undertow
  import io.undertow.server.HttpHandler
  import io.undertow.server.HttpServerExchange
  import io.undertow.util.Headers

  case class Context(
      errors: mutable.ListBuffer[ErrorReport] = mutable.ListBuffer.empty,
      crashes: mutable.ListBuffer[CrashReport] = mutable.ListBuffer.empty,
  )

  def apply(
      host: String,
      preferredPort: Int,
  )(implicit ctx: Context): Undertow = {
    val port = freePort(host, preferredPort)

    val baseHandler = path()
      .withEndpoint(
        TelemetryService.sendErrorReportEndpoint,
        _.errors,
      )
      .withEndpoint(
        TelemetryService.sendCrashReportEndpoint,
        _.crashes,
      )
    Undertow.builder
      .addHttpListener(port, host)
      .setHandler(baseHandler)
      .build()
  }

  implicit class EndpointOps(private val handler: PathHandler) extends AnyVal {
    def withEndpoint[In](
        endpoint: FireAndForgetEndpoint[In],
        eventCollectionsSelector: Context => mutable.ListBuffer[In],
    )(implicit ctx: Context): PathHandler = handler.addExactPath(
      endpoint.uri,
      new BlockingHandler(
        new SimpleHttpHandler[In](
          endpoint,
          eventCollectionsSelector(ctx),
        )
      ),
    )
  }

  private class SimpleHttpHandler[In](
      endpoint: FireAndForgetEndpoint[In],
      receivedEvents: mutable.ListBuffer[In],
  ) extends HttpHandler {
    override def handleRequest(exchange: HttpServerExchange): Unit = {
      exchange.getRequestReceiver().receiveFullString {
        (exchange: HttpServerExchange, json: String) =>
          receivedEvents += endpoint.decodeInput(json).get
          exchange
            .getResponseHeaders()
            .put(Headers.CONTENT_TYPE, "application/json")
          exchange
            .getResponseSender()
            .send("")
      }
    }
  }

  def address(server: Undertow): String =
    server.getListenerInfo.asScala.headOption match {
      case Some(listener) =>
        s"${listener.getProtcol}:/" + listener.getAddress.toString
      case None => ""
    }

  final def freePort(host: String, port: Int, maxRetries: Int = 20): Int = {
    try {
      val socket = new ServerSocket()
      try {
        socket.bind(new InetSocketAddress(host, port))
        socket.getLocalPort()
      } finally {
        socket.close()
      }
    } catch {
      case NonFatal(_: IOException) if maxRetries > 0 =>
        freePort(host, port + 1, maxRetries - 1)
    }
  }
}
