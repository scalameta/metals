package scala.meta.metals

import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsLanguageServer
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.Trace
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.logging.MetalsLogger

import org.eclipse.lsp4j.jsonrpc.Launcher
import coursierapi.Complete
import scala.jdk.CollectionConverters._
import scala.util.Try

object Main {
  def main(args: Array[String]): Unit = {
    if (args.exists(Set("-v", "--version", "-version"))) {
      val api: Complete = coursierapi.Complete
        .create()
        .withInput("org.scalameta:mtags")

      val supported =
        BuildInfo.supportedScalaVersions.toSet

      import scala.collection.parallel._

      val additional =
        api
          .complete()
          .getCompletions()
          .asScala
          .toVector
          .flatMap { case artif =>
            Try {
              val scalaVer = artif.stripPrefix("mtags_")
              val List(epoch, major, rest) = scalaVer.split("\\.", 3).toList

              if (epoch == "2" && major.toInt < 11) None // 2.10 and below
              else if (rest.endsWith("-NIGHTLY")) None // dotty nightlies
              else if (supported(scalaVer))
                None // versions we already know about
              else if (epoch == "0") None // old dotty versions
              else if (scalaVer.contains("-M")) None // milestone versions
              else Some(scalaVer)
            }.toOption.flatten
          }
          .toParArray
          .filter { scalaVer =>
            val res = coursierapi.Complete
              .create()
              .withInput(
                s"org.scalameta:mtags_$scalaVer:${BuildInfo.metalsVersion}"
              )
              .complete()

            res.getCompletions().asScala.contains(BuildInfo.metalsVersion)
          }
          .toVector

      val supportedScala2Versions =
        (BuildInfo.supportedScala2Versions ++ additional.filter(
          _.startsWith("2.")
        )).distinct
          .groupBy(ScalaVersions.scalaBinaryVersionFromFullVersion)
          .toSeq
          .sortBy(_._1)
          .map { case (_, versions) => versions.mkString(", ") }
          .mkString("\n#       ")

      val supportedScala3Versions =
        (BuildInfo.supportedScala3Versions ++ additional.filter(
          _.startsWith("3.")
        )).sorted.distinct.mkString(", ")

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
    val initialConfig = MetalsServerConfig.default
    val server = new MetalsLanguageServer(
      ec,
      redirectSystemOut = true,
      charset = StandardCharsets.UTF_8,
      initialConfig = initialConfig,
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
