package scala.meta.internal.metals.mcp

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.stream.Collectors

import scala.meta.internal.metals.mcp.LoggingServletTransportProvider.traceHeader

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper

class LoggingServletTransportProvider(
    objectMapper: ObjectMapper,
    baseUrl: String,
    sseEndpoint: String,
    trace: Option[PrintWriter],
) extends HttpServletSseServerTransportProvider {

  override def doPost(
      request: HttpServletRequest,
      response: HttpServletResponse,
  ): Unit = {
    val body =
      request
        .getReader()
        .lines()
        .collect(Collectors.joining(System.lineSeparator()))
    trace.foreach { trace =>
      trace.println(s"""|$traceHeader Sending request
                        |$body""".stripMargin)
      trace.flush()
    }
    val wrappedRequest = new HttpServletRequestWrapper(request) {
      private val bodyBytes = body.getBytes(StandardCharsets.UTF_8)
      override def getInputStream(): ServletInputStream = {
        val inputStream = new ByteArrayInputStream(bodyBytes)
        new ServletInputStream {
          override def read(): Int = inputStream.read()
          override def isFinished(): Boolean = inputStream.available() == 0
          override def isReady(): Boolean = true
          override def setReadListener(readListener: ReadListener): Unit = {}
        }
      }
      override def getReader(): BufferedReader = {
        new BufferedReader(
          new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)
        )
      }
    }
    super.doPost(wrappedRequest, response)
  }

  override def doGet(
      request: HttpServletRequest,
      response: HttpServletResponse,
  ): Unit = {
    val wrappedResponse = new LoggingHttpServletResponse(response, trace)
    super.doGet(request, wrappedResponse)
    val pathInfo = request.getPathInfo();
    if (sseEndpoint.equals(pathInfo)) {
      val scheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
      // Scheduling a ping task to keep the connection alive,
      // see: https://github.com/AltimateAI/vscode-dbt-power-user/pull/1631 or
      // https://github.com/modelcontextprotocol/rust-sdk/pull/74
      val pingTask = new Runnable {
        override def run(): Unit = {
          try {
            response.getWriter().write(": ping\n\n")
            response.getWriter().flush()
          } catch {
            case _: Exception => scheduler.shutdown()
          }
        }
      }
      scheduler.scheduleAtFixedRate(
        pingTask,
        30,
        30,
        java.util.concurrent.TimeUnit.SECONDS,
      )
    }
  }
}

object LoggingServletTransportProvider {

  def traceHeader: String =
    s"[Trace - ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] "
}

private class LoggingPrintWriter(
    underlying: PrintWriter,
    trace: Option[PrintWriter],
) extends PrintWriter(underlying) {
  override def write(s: String): Unit = {
    if (!s.startsWith(": ping")) {
      trace.foreach { trace =>
        trace.println(
          s"""|${traceHeader} Received response
              |$s""".stripMargin
        )
        trace.flush()
      }
    }
    super.write(s)
  }
}

private class LoggingHttpServletResponse(
    underlying: HttpServletResponse,
    trace: Option[PrintWriter],
) extends HttpServletResponseWrapper(underlying) {
  private val loggingWriter =
    new LoggingPrintWriter(super.getWriter(), trace)
  override def getWriter(): PrintWriter = loggingWriter
}
