package scala.meta.internal.bsp

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Properties

import scala.meta.internal.metals.BuildServerConnectionFactory
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails

/**
 * A connection factory for a generic BSP server.
 * It supports integration with build tools such as Bazel and SBT.
 * It is worth noting that Bloop has a dedicated connection factory
 * which doesn't extend this class.
 *
 * @see [[scala.meta.internal.metals.BloopServerConnectionFactory]]
 */
abstract class BspServerConnectionFactory(
    projectDirectory: AbsolutePath,
    bspTraceRoot: AbsolutePath,
    localClient: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    requestTimeOutNotification: DismissedNotifications#Notification,
    reconnectNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig,
    private val details: BspConnectionDetails,
    bspStatusOpt: Option[ConnectionBspStatus] = None,
    workDoneProgress: WorkDoneProgress,
    progress: TaskProgress,
) extends BuildServerConnectionFactory(
      projectDirectory,
      bspTraceRoot,
      localClient,
      languageClient,
      requestTimeOutNotification,
      reconnectNotification,
      config,
      details.getName(),
      bspStatusOpt,
      supportsWrappedSources = None,
      workDoneProgress = workDoneProgress,
    ) {
  override protected def connect()(implicit
      ec: ExecutionContextExecutorService
  ): Future[SocketConnection] = {

    val args = details.getArgv.asScala.toList
      /* When running on Windows, the sbt script is passed as an argument to the
       * BSP server. If the script path is encoded using URI encoding the server
       * will fail to start. The workaround is to add `file://`.
       * https://github.com/scalameta/metals/issues/5027
       * and also:
       * https://learn.microsoft.com/en-us/troubleshoot/windows-client/networking/url-encoding-unc-paths-not-url-decoded
       */
      .map { arg =>
        if (
          Properties.isWin && arg.contains("-Dsbt.script=") &&
          !arg.contains("file://") && URIEncoderDecoder.decode(arg) != arg
        )
          arg.replace("-Dsbt.script=", "-Dsbt.script=file://")
        else
          arg
      }

    val variables =
      // With Bazel for example changing JAVA_HOME might cause Bazel to restart on shell
      if (
        sys.env.contains("JAVA_HOME") && details.getName().contains("bazel")
      ) {
        userConfiguration().javaHome.zip(sys.env.get("JAVA_HOME")) match {
          case Some((metalsHome, envHome)) if metalsHome != envHome =>
            scribe.warn(
              s"JAVA_HOME set by Metals (${metalsHome}) would be different than the one set in the environment ($envHome), " +
                "which might cause Bazel to restart on shell, so Metals will not override it."
            )
          case _ =>
        }
        Map.empty[String, String]
      } else JdkSources.envVariables(userConfiguration().javaHome)

    // Convert credential-related system properties to environment variables
    // This helps BSP servers (like sbt) access custom repository credentials
    val credentialEnvVars = sys.props.collect {
      case (key, value) if key == "coursier.credentials" =>
        "COURSIER_CREDENTIALS" -> value
    }

    val allVariables =
      variables ++ credentialEnvVars + ("SCALA_CLI_POWER" -> "true")

    scribe.info(s"Running BSP server $args")
    val proc = SystemProcess.run(
      args,
      projectDirectory,
      redirectErrorOutput = false,
      allVariables,
      processOut = None,
      processErr = Some(l => scribe.info("BSP server: " + l)),
      discardInput = false,
      threadNamePrefix = s"bsp-${details.getName}",
    )

    val output = new ClosableOutputStream(
      proc.outputStream,
      s"${details.getName} output stream",
    )
    val input = new QuietInputStream(
      proc.inputStream,
      s"${details.getName} input stream",
    )

    val finished = Promise[Unit]()
    proc.complete.ignoreValue.onComplete { res =>
      progress.message = s"connected to ${details.getName()}"
      finished.tryComplete(res)
    }

    Future.successful {
      SocketConnection(
        details.getName(),
        output,
        input,
        List(
          Cancelable(() => proc.cancel)
        ),
        finished,
      )
    }
  }
}
