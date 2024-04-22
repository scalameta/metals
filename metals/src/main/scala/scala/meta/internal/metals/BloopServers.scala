package scala.meta.internal.metals

import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.bsp.ConnectionBspStatus
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.core.Shell
import bloop.launcher.LauncherMain
import org.eclipse.lsp4j.MessageType

/**
 * Establishes a connection with a bloop server using Bloop Launcher.
 *
 * Connects to a running bloop server instance if it is installed on the user
 * machine and starts a new one if it isn't. Alternatively user can use the
 * coursier command to launch it:
 *
 * coursier launch ch.epfl.scala:bloopgun-core_2.12:{bloop-version} -- about
 *
 * Eventually, this class may be superseded by "BSP connection protocol":
 * https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BloopServers(
    client: MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    tables: Tables,
    config: MetalsServerConfig,
)(implicit ec: ExecutionContextExecutorService) {

  import BloopServers._

  private val bloopJsonPath: Option[AbsolutePath] =
    getBloopFilePath(fileName = "bloop.json")

  // historically used file created by Metals
  // now we just delete it if existed to cleanup
  private val bloopLockFile: Option[AbsolutePath] =
    getBloopFilePath(fileName = "created_by_metals.lock")

  private def metalsJavaHome = sys.props.get("java.home")

  def shutdownServer(): Boolean = {
    val dummyIn = new ByteArrayInputStream(new Array(0))
    val cli = new BloopgunCli(
      BuildInfo.bloopVersion,
      dummyIn,
      System.out,
      System.err,
      Shell.default,
    )
    val result = cli.run(Array("exit")) == 0
    if (!result) {
      scribe.warn("There were issues stopping the Bloop server.")
      scribe.warn(
        "If it doesn't start back up you can run the `build-restart` command manually."
      )
    }
    result
  }

  def newServer(
      projectRoot: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      userConfiguration: UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {
    val bloopVersion = userConfiguration.currentBloopVersion
    BuildServerConnection
      .fromSockets(
        projectRoot,
        bspTraceRoot,
        client,
        languageClient,
        () =>
          connectToLauncher(bloopVersion, config.bloopPort, userConfiguration),
        tables.dismissedNotifications.ReconnectBsp,
        tables.dismissedNotifications.RequestTimeout,
        config,
        name,
        bspStatusOpt,
      )
  }

  /**
   * Ensure Bloop is running the intended version that the user has passed
   * in via UserConfiguration. If not, shut Bloop down and reconnect to it.
   *
   * @param expectedVersion desired version that the user has passed in. This
   *                        could either be a newly passed in version from the
   *                        user or the default Bloop version.
   * @param runningVersion  the current running version of Bloop.
   * @param userDefinedNew  whether or not the user has defined a new version.
   * @param userDefinedOld  whether or not the user has the version running
   *                        defined or if they are just running the default.
   * @param reconnect       function to connect back to the build server.
   */
  def ensureDesiredVersion(
      expectedVersion: String,
      runningVersion: String,
      userDefinedNew: Boolean,
      userDefinedOld: Boolean,
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    val correctVersionRunning = expectedVersion == runningVersion
    val changedToNoVersion = userDefinedOld && !userDefinedNew
    val versionChanged = userDefinedNew && !correctVersionRunning
    val versionRevertedToDefault = changedToNoVersion && !correctVersionRunning

    if (versionRevertedToDefault || versionChanged) {
      languageClient
        .showMessageRequest(
          Messages.BloopVersionChange.params()
        )
        .asScala
        .flatMap {
          case item if item == Messages.BloopVersionChange.reconnect =>
            shutdownServer()
            reconnect().ignoreValue
          case _ =>
            Future.unit
        }
    } else {
      Future.unit
    }
  }

  private def writeJVMPropertiesToBloopGlobalJsonFile(
      maybeBloopJvmProperties: List[String],
      newJavaHome: Option[String],
  ): Try[Unit] = Try {
    if (newJavaHome.isDefined || maybeBloopJvmProperties.nonEmpty) {
      val javaOptionsField =
        if (maybeBloopJvmProperties.nonEmpty)
          Some(
            "javaOptions" -> ujson.Arr(
              maybeBloopJvmProperties.map(opt => ujson.Str(opt.trim())): _*
            )
          )
        else None
      val fields: List[(String, ujson.Value)] =
        List(
          newJavaHome.map(v => "javaHome" -> ujson.Str(v.trim())),
          javaOptionsField,
        ).flatten
      val obj = ujson.Obj.from(fields)
      val jvmPropertiesString = ujson.write(obj)

      bloopJsonPath.foreach(_.writeText(jvmPropertiesString))
      bloopLockFile.foreach(_.deleteIfExists())
    }
  }

  private def updateBloopGlobalJsonFileThenRestart(
      maybeBloopJvmProperties: List[String],
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    languageClient
      .showMessageRequest(
        Messages.BloopJvmPropertiesChange.params()
      )
      .asScala
      .flatMap {
        case messageActionItem
            if messageActionItem == Messages.BloopJvmPropertiesChange.reconnect =>
          val javaHome = getJavaHomeForBloopJson.flatMap {
            case AlreadyWritten(javaHome) => Some(javaHome)
            case _ => metalsJavaHome
          }
          writeJVMPropertiesToBloopGlobalJsonFile(
            maybeBloopJvmProperties,
            javaHome,
          ) match {
            case Failure(exception) => Future.failed(exception)
            case Success(_) =>
              shutdownServer()
              reconnect().ignoreValue
          }
        case _ =>
          Future.unit
      }

  }

  private def maybeLoadBloopGlobalJsonFile(
      bloopGlobalJsonFilePath: AbsolutePath
  ): (Option[String], List[String]) = {

    val maybeLinkedHashMap =
      bloopGlobalJsonFilePath.readTextOpt.map(ujson.read(_)).flatMap(_.objOpt)

    val maybeJavaHome = for {
      linkedHashMap <- maybeLinkedHashMap
      javaHomeValue <- linkedHashMap.get("javaHome")
      javaHomeStr <- javaHomeValue.strOpt
    } yield javaHomeStr

    val maybeJavaOptions = for {
      linkedHashMap <- maybeLinkedHashMap
      javaOptionsValue <- linkedHashMap.get("javaOptions")
      javaOptionsValueArray <- javaOptionsValue.arrOpt
    } yield javaOptionsValueArray.flatMap(_.strOpt).toList
    (maybeJavaHome, maybeJavaOptions.getOrElse(Nil))
  }

  /**
   * First we check if the user requested to update the Bloop JVM
   * properties through the extension.
   * <p>Through consultation with the user through appropriate
   * dialogues we decide if we should
   * <ul>
   * <li>overwrite the contents of the Bloop Global Json file with the
   * requested JVM properties and Metal's JavaHome variables; and then
   * restart the Bloop server</li>
   * <li>or alternatively, leave things untouched</li>
   * </ul>
   *
   * @param requestedBloopJvmProperties Bloop JVM Properties requested
   *                                    through the Metals Extension settings
   * @param reconnect                   function to connect back to the
   *                                    build server.
   * @return `Future.successful` if the purpose is achieved or `Future.failure`
   *         if a problem occurred such as lacking enough permissions to open or
   *         write to files
   */
  def ensureDesiredJvmSettings(
      requestedBloopJvmProperties: List[String],
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    val result =
      for {
        bloopPath <- bloopJsonPath
        if bloopPath.canWrite
        (maybeBloopGlobalJsonJavaHome, maybeBloopGlobalJsonJvmProperties) =
          maybeLoadBloopGlobalJsonFile(bloopPath)
        if (requestedBloopJvmProperties != maybeBloopGlobalJsonJvmProperties)
      } yield updateBloopJvmProperties(
        requestedBloopJvmProperties,
        reconnect,
      )
    result.getOrElse(Future.unit)
  }

  private def updateBloopJvmProperties(
      maybeBloopJvmProperties: List[String],
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    if (tables.dismissedNotifications.UpdateBloopJson.isDismissed) Future.unit
    else {
      updateBloopGlobalJsonFileThenRestart(
        maybeBloopJvmProperties,
        reconnect,
      ) andThen {
        case Failure(exception) =>
          languageClient.showMessage(
            MessageType.Error,
            exception.getMessage,
          )
        case Success(_) => Future.unit
      }
    }
  }

  private def updateBloopJavaHomeBeforeLaunch(
      userConfiguration: UserConfiguration
  ) = {
    // we should set up Java before running Bloop in order to not restart it
    getJavaHomeForBloopJson match {
      case Some(WriteMetalsJavaHome) =>
        metalsJavaHome.foreach { newHome =>
          bloopJsonPath.foreach { bloopPath =>
            scribe.info(s"Setting up current java home $newHome in $bloopPath")
          }
        }
        // we want to use the same java version as Metals, so it's ok to use java.home
        writeJVMPropertiesToBloopGlobalJsonFile(
          userConfiguration.bloopJvmProperties.getOrElse(Nil),
          metalsJavaHome,
        )
      case Some(OverrideWithMetalsJavaHome(javaHome, oldJavaHome, opts)) =>
        scribe.info(
          s"Replacing bloop java home $oldJavaHome with java home at $javaHome."
        )
        writeJVMPropertiesToBloopGlobalJsonFile(opts, Some(javaHome))
      case _ =>
    }
  }

  private def getJavaHomeForBloopJson: Option[BloopJavaHome] =
    bloopJsonPath match {
      case Some(bloopPath) if !bloopPath.exists =>
        Some(WriteMetalsJavaHome)
      case Some(bloopPath) if bloopPath.exists =>
        maybeLoadBloopGlobalJsonFile(bloopPath) match {
          case (Some(javaHome), opts) =>
            metalsJavaHome match {
              case Some(metalsJava) =>
                (
                  JdkVersion.maybeJdkVersionFromJavaHome(javaHome),
                  JdkVersion.maybeJdkVersionFromJavaHome(metalsJava),
                ) match {
                  case (Some(writtenVersion), Some(metalsJavaVersion))
                      if writtenVersion.major >= metalsJavaVersion.major =>
                    Some(AlreadyWritten(javaHome))
                  case _ if javaHome != metalsJava =>
                    Some(OverrideWithMetalsJavaHome(metalsJava, javaHome, opts))
                  case _ => Some(AlreadyWritten(javaHome))
                }
              case None => Some(AlreadyWritten(javaHome))
            }
          case _ => None
        }
      case _ => None
    }

  private def connectToLauncher(
      bloopVersion: String,
      bloopPort: Option[Int],
      userConfiguration: UserConfiguration,
  ): Future[SocketConnection] = {

    updateBloopJavaHomeBeforeLaunch(userConfiguration)
    val launcherInOutPipe = Pipe.open()
    val launcherIn = new QuietInputStream(
      Channels.newInputStream(launcherInOutPipe.source()),
      "Bloop InputStream",
    )
    val clientOut = new ClosableOutputStream(
      Channels.newOutputStream(launcherInOutPipe.sink()),
      "Bloop OutputStream",
    )

    val clientInOutPipe = Pipe.open()
    val clientIn = Channels.newInputStream(clientInOutPipe.source())
    val launcherOut = Channels.newOutputStream(clientInOutPipe.sink())

    val serverStarted = Promise[Unit]()
    val bloopLogs = new OutputStream {
      private lazy val b = new StringBuilder

      override def write(byte: Int): Unit = byte.toChar match {
        case c => b.append(c)
      }

      def logs = b.result.linesIterator
    }

    val launcher =
      new LauncherMain(
        launcherIn,
        launcherOut,
        new PrintStream(bloopLogs, true),
        StandardCharsets.UTF_8,
        Shell.default,
        userNailgunHost = None,
        userNailgunPort = bloopPort,
        serverStarted,
      )

    val finished = Promise[Unit]()
    val job = ec.submit(new Runnable {
      override def run(): Unit = {
        launcher.runLauncher(
          bloopVersion,
          skipBspConnection = false,
          Nil,
        )
        finished.success(())
      }
    })

    serverStarted.future
      .map { _ =>
        SocketConnection(
          name,
          clientOut,
          clientIn,
          List(
            Cancelable { () =>
              clientOut.flush()
              clientOut.close()
            },
            Cancelable(() => job.cancel(true)),
          ),
          finished,
        )
      }
      .recover { case t: Throwable =>
        bloopLogs.logs.foreach(scribe.error(_))
        throw t
      }
  }
}

object BloopServers {
  val name = "Bloop"

  def getBloopFilePath(fileName: String): Option[AbsolutePath] = {
    sys.props.get("user.home").map { home =>
      AbsolutePath(
        Paths
          .get(home)
          .resolve(s".bloop/$fileName")
      )
    }
  }

  sealed trait BloopJavaHome
  case class AlreadyWritten(javaHome: String) extends BloopJavaHome
  case class OverrideWithMetalsJavaHome(
      javaHome: String,
      oldJavaHome: String,
      opts: List[String],
  ) extends BloopJavaHome
  object WriteMetalsJavaHome extends BloopJavaHome
}
