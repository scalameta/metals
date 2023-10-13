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
import scala.meta.internal.metals.BloopJsonUpdateCause.BloopJsonUpdateCause
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.core.Shell
import bloop.launcher.LauncherMain
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Position

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
  private val bloopLockFile: Option[AbsolutePath] =
    getBloopFilePath(fileName = "created_by_metals.lock")

  private def bloopLastModifiedTime: Long = bloopJsonPath
    .flatMap(path => Try(path.toNIO.toFile().lastModified()).toOption)
    .getOrElse(0L)

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
      maybeJavaHome: Option[String],
  ): Try[Unit] = Try {
    if (maybeJavaHome.isDefined || maybeBloopJvmProperties.nonEmpty) {
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
          maybeJavaHome.map(v => "javaHome" -> ujson.Str(v.trim())),
          javaOptionsField,
        ).flatten
      val obj = ujson.Obj.from(fields)
      val jvmPropertiesString = ujson.write(obj)

      bloopJsonPath.foreach(_.writeText(jvmPropertiesString))
      bloopLockFile.foreach(_.writeText(bloopLastModifiedTime.toString()))
    }
  }

  private def processUserPreferenceForBloopJvmProperties(
      messageActionItem: MessageActionItem,
      maybeBloopJvmProperties: List[String],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    (messageActionItem, bloopJsonPath) match {
      case (item, _)
          if item == Messages.BloopGlobalJsonFilePremodified.applyAndRestart =>
        writeJVMPropertiesToBloopGlobalJsonFile(
          maybeBloopJvmProperties,
          maybeJavaHome,
        ) match {
          case Failure(exception) => Future.failed(exception)
          case Success(_) =>
            shutdownServer()
            reconnect().ignoreValue
        }

      case (item, Some(bloopPath))
          if item == Messages.BloopGlobalJsonFilePremodified.openGlobalJsonFile =>
        val position = new Position(0, 0)
        val range = new org.eclipse.lsp4j.Range(position, position)
        val command = ClientCommands.GotoLocation.toExecuteCommandParams(
          ClientCommands.WindowLocation(
            bloopPath.toURI.toString,
            range,
          )
        )
        Future.successful(languageClient.metalsExecuteClientCommand(command))

      case (item, _)
          if item == Messages.BloopGlobalJsonFilePremodified.useGlobalFile =>
        tables.dismissedNotifications.UpdateBloopJson.dismissForever()
        Future.unit
      case _ => Future.unit

    }
  }

  private def updateBloopGlobalJsonFileThenRestart(
      maybeBloopJvmProperties: List[String],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange],
      bloopJsonUpdateCause: BloopJsonUpdateCause,
  ): Future[Unit] = {
    languageClient
      .showMessageRequest(
        Messages.BloopJvmPropertiesChange.params(bloopJsonUpdateCause)
      )
      .asScala
      .flatMap {
        case messageActionItem
            if messageActionItem == Messages.BloopJvmPropertiesChange.reconnect =>
          writeJVMPropertiesToBloopGlobalJsonFile(
            maybeBloopJvmProperties,
            maybeJavaHome,
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
   * Determine whether or not we need to update the javaHome setting in the bloop.json file.
   *
   * @param metalsJavaHome javaHome being passed in from the user
   * @param bloopJavaHome bloop javaHome that is in the global config
   * @return whether or not the javaHome needs to be updated
   */
  private def needsJavaHomeUpdate(
      metalsJavaHome: Option[String],
      bloopJavaHome: Option[String],
  ) = {
    (metalsJavaHome, bloopJavaHome) match {
      // Metals is set but Bloop isn't
      case (Some(_), None) => true
      // Metals and Bloop are set, but they aren't the same
      case (Some(m), Some(b)) if m != b => true
      case _ => false
    }
  }

  /**
   * First we check if the user requested to update the Bloop JVM
   * properties through the extension.
   * <p>If so, we also check if the Bloop's Global Json file exists
   * and if it was pre-modified by the user.
   * <p>Then, through consultation with the user through appropriate
   * dialogues we decide if we should
   * <ul>
   * <li>overwrite the contents of the Bloop Global Json file with the
   * requested JVM properties and Metal's JavaHome variables; and then
   * restart the Bloop server</li>
   * <li>or alternatively, leave things untouched</li>
   * </ul>
   *
   * @param maybeRequestedBloopJvmProperties Bloop JVM Properties requested
   *                                         through the Metals Extension settings
   * @param reconnect                        function to connect back to the
   *                                         build server.
   * @return `Future.successful` if the purpose is achieved or `Future.failure`
   *         if a problem occured such as lacking enough permissions to open or
   *         write to files
   */
  def ensureDesiredJvmSettings(
      maybeRequestedBloopJvmProperties: Option[List[String]],
      maybeRequestedMetalsJavaHome: Option[String],
      reconnect: () => Future[BuildChange],
  ): Future[Unit] = {
    val result =
      for {
        bloopPath <- bloopJsonPath
        if bloopPath.canWrite
        (maybeBloopGlobalJsonJavaHome, maybeBloopGlobalJsonJvmProperties) =
          maybeLoadBloopGlobalJsonFile(bloopPath)
        bloopJsonUpdateCause <-
          if (
            maybeRequestedBloopJvmProperties
              .exists(requested =>
                requested != maybeBloopGlobalJsonJvmProperties
              )
          ) Some(BloopJsonUpdateCause.JVM_OPTS)
          else if (
            needsJavaHomeUpdate(
              maybeRequestedMetalsJavaHome,
              maybeBloopGlobalJsonJavaHome,
            )
          )
            Some(BloopJsonUpdateCause.JAVA_HOME)
          else None
        maybeBloopJvmProperties = maybeRequestedBloopJvmProperties.getOrElse(
          maybeBloopGlobalJsonJvmProperties
        )
      } yield updateBloopJvmProperties(
        maybeBloopJvmProperties,
        maybeRequestedMetalsJavaHome,
        reconnect,
        bloopJsonUpdateCause,
      )

    result.getOrElse {
      Future.unit
    }
  }

  private def updateBloopJvmProperties(
      maybeBloopJvmProperties: List[String],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange],
      bloopJsonUpdateCause: BloopJsonUpdateCause,
  ): Future[Unit] = {
    val lockFileTime = bloopLockFile
      .flatMap(file => Try(file.readText.toLong).toOption)
      .getOrElse(0L)
    if (tables.dismissedNotifications.UpdateBloopJson.isDismissed) Future.unit
    else if (
      bloopJsonPath.exists(_.exists) && bloopLastModifiedTime > lockFileTime
    ) {
      // the global json file was previously modified by the user through other means;
      // therefore overwriting it requires user input
      languageClient
        .showMessageRequest(
          Messages.BloopGlobalJsonFilePremodified.params(bloopJsonUpdateCause)
        )
        .asScala
        .flatMap {
          processUserPreferenceForBloopJvmProperties(
            _,
            maybeBloopJvmProperties,
            maybeJavaHome,
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
    } else {
      // bloop global json file did not exist; or it was last modified by metals;
      // hence it can get created or overwritten by Metals with no worries
      // about overriding the user preferred settings
      updateBloopGlobalJsonFileThenRestart(
        maybeBloopJvmProperties,
        maybeJavaHome,
        reconnect,
        bloopJsonUpdateCause,
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
    def metalsJavaHome =
      userConfiguration.javaHome
        .orElse(sys.env.get("JAVA_HOME"))
        .orElse(sys.props.get("java.home"))
    // we should set up Java before running Bloop in order to not restart it
    bloopJsonPath match {
      case Some(bloopPath) if !bloopPath.exists =>
        metalsJavaHome.foreach { newHome =>
          scribe.info(s"Setting up current java home $newHome in $bloopPath")
        }
        // we want to use the same java version as Metals, so it's ok to use java.home
        writeJVMPropertiesToBloopGlobalJsonFile(
          userConfiguration.bloopJvmProperties.getOrElse(Nil),
          metalsJavaHome,
        )
      case Some(bloopPath) if bloopPath.exists =>
        maybeLoadBloopGlobalJsonFile(bloopPath) match {
          case (Some(javaHome), opts) =>
            Try {
              val homePath = AbsolutePath(Paths.get(javaHome))
              // fix java home in case it changed
              if (!homePath.exists) {
                scribe.info(
                  s"Detected non existing java path in $bloopPath file"
                )
                writeJVMPropertiesToBloopGlobalJsonFile(
                  opts,
                  metalsJavaHome,
                )
                metalsJavaHome.foreach { newHome =>
                  scribe.info(
                    s"Replacing it with java home at $newHome"
                  )
                }
              } else {
                scribe.info(s"Bloop uses $javaHome defined at $bloopPath")
                if (opts.nonEmpty)
                  scribe.info(
                    s"Bloop currently uses settings: ${opts.mkString(",")}"
                  )
              }
            }
          case _ =>
        }
      case _ =>
    }
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

object BloopJsonUpdateCause extends Enumeration {
  type BloopJsonUpdateCause = Value
  val JAVA_HOME: Value = Value("Metals Java Home")
  val JVM_OPTS: Value = Value("Bloop JVM Properties")
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
}
