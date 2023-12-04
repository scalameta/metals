package scala.meta.metals

import java.util.Optional
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.TelemetryClient
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.telemetry.CrashReport
import scala.meta.internal.telemetry.ExceptionSummary

import org.eclipse.lsp4j.jsonrpc.Launcher

object Main {
  def main(args: Array[String]): Unit = {
    if (args.exists(Set("-v", "--version", "-version"))) {
      val supportedScala2Versions =
        BuildInfo.supportedScala2Versions
          .groupBy(ScalaVersions.scalaBinaryVersionFromFullVersion)
          .toSeq
          .sortBy(_._1)
          .map { case (_, versions) => versions.mkString(", ") }
          .mkString("\n#       ")

      val supportedScala3Versions =
        BuildInfo.supportedScala3Versions.sorted.mkString(", ")

      println(
        s"""|metals ${BuildInfo.metalsVersion}
            |
            |# Note:
            |#   Supported Scala versions:
            |#     Scala 3: $supportedScala3Versions
            |#     Scala 2:
            |#       $supportedScala2Versions
            |""".stripMargin
      )

      sys.exit(0)
    }
    val systemIn = System.in
    val systemOut = System.out
    MetalsLogger.redirectSystemOut(Trace.metalsLog)
    val tracePrinter = Trace.setupTracePrinter("LSP")
    val exec = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(exec)
    val sh = Executors.newSingleThreadScheduledExecutor()
    val server = new MetalsLanguageServer(ec, sh)
    try {
      val launcher = new Launcher.Builder[MetalsLanguageClient]()
        .traceMessages(tracePrinter.orNull)
        .setExecutorService(exec)
        .setInput(systemIn)
        .setOutput(systemOut)
        .setRemoteInterface(classOf[MetalsLanguageClient])
        .setLocalService(server)
        .create()
      val clientProxy = launcher.getRemoteProxy
      // important, plug language client before starting listening!
      server.connectToLanguageClient(clientProxy)
      launcher.startListening().get()
    } catch {
      case NonFatal(e) =>
        trySendCrashReport(e, server)
        e.printStackTrace(systemOut)
        sys.exit(1)
    } finally {
      server.cancelAll()
      ec.shutdownNow()
      sh.shutdownNow()

      sys.exit(0)
    }
  }

  private def trySendCrashReport(
      error: Throwable,
      server: MetalsLanguageServer,
  ): Unit = try {
    val telemetryLevel = server.getTelemetryLevel()
    if (telemetryLevel.reportCrash) {
      val telemetry = new TelemetryClient(() => telemetryLevel)
      telemetry.sendCrashReport(
        new CrashReport(
          ExceptionSummary.fromThrowable(error, identity),
          this.getClass().getName(),
          Optional.of(BuildInfo.metalsVersion),
          Optional.empty(),
        )
      )
    }
  } catch {
    case err: Throwable =>
      System.err.println(s"Failed to send crash report, $err")
  }

}
