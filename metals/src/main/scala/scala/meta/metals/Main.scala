package scala.meta.metals

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.logging.MetalsLogger

import org.eclipse.lsp4j.jsonrpc.Launcher

object Main extends SupportedScalaVersions {

  override protected def formatSingle(
      major: String,
      versions: Seq[String],
  ): String = {

    s"""| - Scala ${major}:
        |   ${versions.mkString(", ")}
        |
        |""".stripMargin
  }

  def main(args: Array[String]): Unit = {
    if (args.exists(Set("-v", "--version", "-version"))) {
      println(
        s"""|metals ${BuildInfo.metalsVersion}
            |
            |${supportedVersionsString(BuildInfo.metalsVersion, 15.seconds)}
            |""".stripMargin
      )
      sys.exit(0)
    }
    val systemIn = System.in
    val systemOut = System.out
    val exec = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(exec)
    MetalsLogger.redirectSystemOut(Trace.metalsLog(ec))
    val tracePrinter = Trace.setupTracePrinter("LSP")(ec)
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
        e.printStackTrace(systemOut)
        sys.exit(1)
    } finally {
      server.cancelAll()
      ec.shutdownNow()
      sh.shutdownNow()

      sys.exit(0)
    }
  }

}
