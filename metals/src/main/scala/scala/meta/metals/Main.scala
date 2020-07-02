package scala.meta.metals

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.GlobalTrace
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig

import org.eclipse.lsp4j.jsonrpc.Launcher

object Main {
  def main(args: Array[String]): Unit = {
    if (args.exists(Set("-v", "--version", "-version"))) {
      val supportedScalaVersions =
        BuildInfo.supportedScalaVersions.sorted.mkString(", ")

      println(
        s"""|metals ${BuildInfo.metalsVersion}
            |
            |# Note:
            |#   supported Scala versions: $supportedScalaVersions""".stripMargin
      )

      sys.exit(0)
    }
    val systemIn = System.in
    val systemOut = System.out
    val tracePrinter = GlobalTrace.setup("LSP")
    val exec = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutorService(exec)
    val initialConfig = MetalsServerConfig.default
    val server = new MetalsLanguageServer(
      ec,
      redirectSystemOut = true,
      charset = StandardCharsets.UTF_8,
      initialConfig = initialConfig
    )
    try {
      scribe.info(s"Starting Metals server with configuration: $initialConfig")
      val launcher = new Launcher.Builder[MetalsLanguageClient]()
        .traceMessages(tracePrinter)
        .setExecutorService(exec)
        .setInput(systemIn)
        .setOutput(systemOut)
        .setRemoteInterface(classOf[MetalsLanguageClient])
        .setLocalService(server)
        .create()
      val clientProxy = launcher.getRemoteProxy
      server.connectToLanguageClient(clientProxy)
      launcher.startListening().get()
    } catch {
      case NonFatal(e) =>
        e.printStackTrace(systemOut)
        sys.exit(1)
    } finally {
      server.cancelAll()
    }
  }

}
