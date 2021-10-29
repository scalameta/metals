package scala.meta.metals

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Trace

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
    val tracePrinter = Trace.setup("LSP", PathIO.workingDirectory)
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
      val launcher = new Launcher.Builder[MetalsLanguageClient]()
        .traceMessages(tracePrinter.orNull)
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
      ec.shutdownNow()

      sys.exit(0)
    }
  }

}
