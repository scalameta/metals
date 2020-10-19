package scala.meta.internal.builds

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.sys.process._

import scala.meta.internal.bsp.BspServers
import scala.meta.internal.bsp.BspSession
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.JvmOpts
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.SbtOpts
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Timer
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import java.nio.file.Path
import scala.util.Failure
import scala.util.Success

/**
 * Used to start an sbt process which will allow for easy bsp connection after
 * creation. In the scenario where there is no `.bsp/sbt.json` created yet, this
 * can be used to ensure the correct version of the server, start it, and then
 * initialize a bsp session. If a `.bsp/sbt.json` already exists, this ensures that
 * sbt is up and running before a bsp session is initialized.
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

  private lazy val tempDir: Path = {
    val dir = Files.createTempDirectory("metals")
    dir.toFile.deleteOnExit()
    dir
  }

  private lazy val embeddedSbtLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  /**
   * Terminate any sbt process that the class may have started.
   */
  def disconnect(): Unit = {
    sbtProcess.map { p =>
      scribe.info(s"Killing running sbt process.")
      p.destroy(false)
      p.waitFor(10, TimeUnit.SECONDS)
    }
  }

  /**
   * Kick off an sbt connection. This is the main entry point in the scenario
   * where the user has a fresh project and no `.bsp/sbt.json` even created
   * yet. This will kick off the process of ensuring that the version of sbt
   * being used supports bsp, launches it, and then attempts to initialize
   * a bsp session.
   */
  def connect(): Future[Unit] = {
    scribe.info("Attempting to connect to sbt BSP server...")
    if (buildTools.isSbt && SbtBuildTool.workspaceSupportsBsp(workspace)) {
      scribe.info("Suitable version of sbt found, attempting to connect...")
      launchAndInit()
    } else {
      scribe.warn(Messages.NoSbtBspSupport.getMessage())
      langaugeClient.showMessage(Messages.NoSbtBspSupport)
      Future.successful(())
    }
  }

  /**
   * Method to check if the MetalsSbtBsp plugin exists in the project, and if
   * not, create it.
   */
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

  /**
   * Start a sbt process by first ensuring the MetalsSbtBsp plugin exists
   * and then starting and managing the connection.
   */
  def runSbtShell(): (NuProcess, SbtProcessHandler) = {
    // TODO there should probably be a check in here to not run another shell
    // if there is already a server running.
    installSbtPlugin()
    // TODO just a placeholder for now. Is there anything extra needed?
    val sbtArgs = List()

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

    val (sbt, handler) = run(
      runCommand,
      workspace
    )
    sbtProcess = Some(sbt)
    (sbt, handler)
  }

  // TODO there isn't a lot of feedback for the user here to know what's happening until the connection is made.
  // It'd be nice to have a progress thing in here
  private def launchAndInit(): Future[Unit] = {

    runDisconnect().map { _ =>
      val (sbt, handler) = runSbtShell()

      scribe.info(s"sbt process started: ${sbt.isRunning}")
      handler.initialized.future.flatMap { _ =>
        scribe.info(s"sbt up and running, attempting to start a bsp session...")
        initialize()
      }
    }

  }

  /**
   * Initialize a bsp connection. Assumes that the sbt server has just been
   * started.
   */
  private def initialize(): Future[Unit] = {

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
          val connectionAttempt = runConnect(session)
          session.mainConnection.onReconnection { newMainConn =>
            val updSession = session.copy(main = newMainConn)
            runConnect(updSession).map(_ => ())
          }
          connectionAttempt.onComplete { whatHappened =>
            val message = whatHappened match {
              case Failure(exception) =>
                s"a failure: ${exception.getMessage()}"
              case Success(_) => "a success"
            }
            scribe.info(s"Completed connection with ${message}")
          }
          connectionAttempt
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
  val sbtLogFile: AbsolutePath = workspace.resolve(Directories.sbtlog)

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
