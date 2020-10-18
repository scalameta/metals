package scala.meta.internal.builds

import sys.process._
import scala.concurrent.Future
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.BspServers
import java.nio.file.Files
import scala.meta.internal.metals.SbtOpts
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.Time
import com.zaxxer.nuprocess.NuProcessBuilder
import scala.concurrent.ExecutionContext
import com.zaxxer.nuprocess.NuProcess
import scala.meta.internal.metals.BspSession
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import java.nio.ByteBuffer
import scala.meta.internal.metals.Directories
import java.nio.charset.StandardCharsets
import scala.concurrent.Promise
import java.util.concurrent.TimeUnit
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.Messages
import ch.epfl.scala.bsp4j.BspConnectionDetails

/**
 * This class is really only used in the case where a user is staring in a
 * fresh workspace that is an sbt build, but doesn't contain a `.bsp/sbt.json`.
 * In this scenario we need to start sbt for the first time and manage it.
 * All consecutive times after this, the bsp discovery will kick in and
 * automatically start the server as needed.
 */
class SbtServer(
    workspace: AbsolutePath,
    buildTools: BuildTools,
    bspServers: BspServers,
    shellRunner: ShellRunner,
    tables: Tables,
    userConfig: () => UserConfiguration,
    runDisconnect: () => Future[Unit],
    runConnect: BspSession => Future[Unit],
    langaugeClient: MetalsLanguageClient
)(implicit ec: ExecutionContext) {
  var sbtProcess: Option[NuProcess] = None

  protected lazy val tempDir = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  lazy val embeddedSbtLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  def disconnect(): Unit = {
    sbtProcess.map { p =>
      scribe.info(s"Killing running sbt process.")
      p.destroy(false)
      p.waitFor(10, TimeUnit.SECONDS)
    }
  }

  def connect(): Future[Unit] = {
    scribe.info("Attempting to connect to sbt BSP server...")
    if (buildTools.isSbt && SbtBuildTool.workspaceSupportsBsp(workspace)) {
      scribe.info("Suitable version of sbt found, attempting to connect...")
      launchSbt()
    } else {
      scribe.warn(Messages.NoSbtBspSupport.getMessage())
      langaugeClient.showMessage(Messages.NoSbtBspSupport)
      Future.successful(())
    }
  }

  private def installSbtPlugin(): Unit = {
    val metalsPluginFile =
      workspace.resolve("project").resolve("MetalsSbtBsp.scala")
    if (!metalsPluginFile.isFile) {
      scribe.info(s"Installalling plugin to ${metalsPluginFile}")
      BuildTool.copyFromResource("MetalsSbtBsp.scala", metalsPluginFile.toNIO)
    } else {
      scribe.info("Skipping installing sbt pluign as it already exists")
    }
  }

  private def runSbtShell(): (NuProcess, SbtProcessHandler) = {
    val sbtArgs = List() // What sbt args are needed?

    val javaArgs = List[String](
      JavaBinary(userConfig().javaHome),
      "-Djline.terminal=jline.UnsupportedTerminal",
      "-Dsbt.log.noformat=true",
      "-Dfile.encoding=UTF-8"
    )
    val jarArgs = List(
      "-jar",
      embeddedSbtLauncher.toString()
    )

    val runCommand = List(
      javaArgs,
      SbtOpts.fromWorkspace(workspace),
      JvmOpts.fromWorkspace(workspace),
      jarArgs,
      sbtArgs
    ).flatten

    run(
      runCommand,
      workspace
    )
  }

  private def launchSbt(): Future[Unit] = {
    installSbtPlugin()

    runDisconnect().map { _ =>
      val (sbt, handler) = runSbtShell()
      sbtProcess = Some(sbt)

      scribe.info(s"sbt process started: ${sbt.isRunning}")
      handler.initialized.future.flatMap { _ =>
        scribe.info(s"sbt up and running, attempting to start a bsp session...")
        initialize()
      }
    }

  }

  def initialize(): Future[Unit] = {

    /**
     * The sbt server just came up at this point, so instead of just searching
     * for .bsp/sbt.json and moving on, we may need to wait a bit for that to
     * be popoulated. Without this, it just misses the creation and moves on.
     *
     * There is a one second delay between each retry
     * @param maxTries max amount of tries to find the .bsp/sbt.json file.
     */
    def findOrWait(maxTries: Int): Option[BspConnectionDetails] = {
      bspServers.findAvailableServers().find(_.getName() == "sbt") match {
        case Some(connectDetails) => Some(connectDetails)
        case None if maxTries > 0 =>
          Thread.sleep(1000)
          findOrWait(maxTries - 1)
        case _ => None
      }
    }

    val possibleConnectionDetails = findOrWait(maxTries = 10)

    val possibleSession = possibleConnectionDetails.map(connectionDetails =>
      bspServers
        .newServer(workspace, connectionDetails)
        .map(BspSession(_, Nil))
    )

    possibleConnectionDetails.foreach(connectionDetails =>
      tables.buildServers.chooseServer(connectionDetails.getName)
    )

    possibleSession match {
      case None =>
        scribe.warn(
          "Started sbt server, but was unable to start a bsp session."
        )
        Future.successful(())
      case Some(sessionF) =>
        sessionF.flatMap { session =>
          val c = runConnect(session)
          session.mainConnection.onReconnection { newMainConn =>
            val updSession = session.copy(main = newMainConn)
            runConnect(updSession).map(_ => ())
          }
          c.onComplete(r => scribe.info(s"Completed connection with ${r}"))
          c.map(_ => ())
        }
    }
  }

  private def run(
      args: List[String],
      directory: AbsolutePath,
      additionalEnv: Map[String, String] = Map.empty
  )(implicit ec: ExecutionContext): (NuProcess, SbtProcessHandler) = {
    val elapsed = new Timer(Time.system)
    scribe.info("Starting background sbt process...")
    val handler = new SbtProcessHandler(workspace)
    val pb = new NuProcessBuilder(handler, args.asJava)
    pb.setCwd(directory.toNIO)
    userConfig().javaHome.foreach(pb.environment().put("JAVA_HOME", _))
    additionalEnv.foreach { case (key, value) =>
      pb.environment().put(key, value)
    }
    val runningProcess = pb.start()
    handler.completeProcess.future.foreach { result =>
      // TODO figure out what's happening here as I never hit this
      scribe.info(s"sbt background process stopped. Ran for $elapsed")
    }
    (runningProcess, handler)
  }

}

class SbtProcessHandler(workspace: AbsolutePath)
    extends NuAbstractProcessHandler {
  val sbtLogFile = workspace.resolve(Directories.sbtlog)

  val initialized: Promise[Boolean] = Promise[Boolean]()
  val completeProcess: Promise[Int] = Promise[Int]()

  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    val msg = StandardCharsets.UTF_8.decode(buffer).toString()
    if (sbtLogFile.isFile) sbtLogFile.appendText(msg)
    else sbtLogFile.writeText(msg)
    if (!initialized.isCompleted && msg.contains("sbt server started at"))
      initialized.trySuccess(true)
    if (
      !initialized.isCompleted && msg.contains(
        "another instance of sbt running on this build"
      )
    )
      initialized.trySuccess(false)
    super.onStdout(buffer, closed)
  }

  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    val msg = StandardCharsets.UTF_8.decode(buffer).toString()
    sbtLogFile.appendText(msg)
    super.onStderr(buffer, closed)
  }

  override def onExit(statusCode: Int): Unit =
    completeProcess.trySuccess(statusCode)
}
