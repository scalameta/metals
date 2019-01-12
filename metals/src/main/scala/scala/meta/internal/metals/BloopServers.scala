package scala.meta.internal.metals

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.sys.process.Process
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

/**
 * Establishes a connection with a bloop server.
 *
 * Connects to a running bloop server instance if it is installed on the user
 * machine and has compatible version (+v1.1.0). Ignores the installed bloop
 * server instance if it is v1.0.0.
 *
 * Otherwise, if the user doesn't have bloop installed on the machine, uses
 * Coursier to fetch the jars for ch.epfl.scala:bloop-frontend and start a new
 * bloop server using classloading. A bloop server that starts via classloading
 * stops when when the metals server stops. Only metals can access the classloaded server,
 * the user cannot call it from a command-line interface.
 *
 * Eventually, this class may be superseded by "BSP connection protocol":
 * https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#bsp-connection-protocol
 */
final class BloopServers(
    sh: ScheduledExecutorService,
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    config: MetalsServerConfig,
    icons: Icons,
    embedded: Embedded,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContextExecutorService) {

  def newServer(): Future[Option[BuildServerConnection]] = Future {
    val bloopLauncher = embedded.embeddedBloopLauncher
    val args = List[String](
      JavaBinary(userConfig().javaHome),
      "-Djna.nosys=true",
      "-jar",
      bloopLauncher.toString(),
      BuildInfo.bloopVersion
    )
    scribe.info(s"launching bloop: ${args.mkString(" ")}")
    val process = new java.lang.ProcessBuilder(args.asJava).start()
    if (!bloopCommandLineIsInstalled()) {
      val skipBsp = args.init ++ List(
        "--skip-bsp-connection",
        BuildInfo.bloopVersion
      )
      val exit = Process(skipBsp, workspace.toFile).!
      scribe.info(s"bloop-launch.jar exit: $exit")
    }
    val server = BuildServerConnection.fromStreams(
      workspace,
      client,
      new QuietOutputStream(process.getOutputStream, "bloop-launch.jar"),
      process.getInputStream,
      List(Cancelable(() => process.destroy())),
      "Bloop"
    )
    Some(server)
  }

  private def bloopCommandLineIsInstalled(): Boolean = {
    try {
      val output = Process(
        List("python", embedded.bloopPy.toString(), "help"),
        cwd = workspace.toFile
      ).!!(ProcessLogger(_ => ()))
      // NOTE: our BSP integration requires bloop 1.1 or higher so we ensure
      // users are on an older version.
      val isOldVersion =
        output.startsWith("bloop 1.0.0\n") ||
          output.startsWith("bloop 0")
      !isOldVersion
    } catch {
      case NonFatal(_) =>
        false
    }
  }
}
