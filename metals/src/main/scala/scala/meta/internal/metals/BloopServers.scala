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
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService) {

  import BloopServers._

  def shutdownServer(): Boolean = {
    val dummyIn = new ByteArrayInputStream(new Array(0))
    val cli = new BloopgunCli(
      BuildInfo.bloopVersion,
      dummyIn,
      System.out,
      System.err,
      Shell.default
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
      workspace: AbsolutePath,
      userConfiguration: UserConfiguration
  ): Future[BuildServerConnection] = {
    val bloopVersion = userConfiguration.currentBloopVersion
    BuildServerConnection
      .fromSockets(
        workspace,
        client,
        languageClient,
        () => connectToLauncher(bloopVersion, config.bloopPort),
        tables.dismissedNotifications.ReconnectBsp,
        config,
        name
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
      reconnect: () => Future[BuildChange]
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
            Future.successful(())
        }
    } else {
      Future.successful(())
    }
  }

  private def writeJVMPropertiesToBloopGlobalJsonFile(
      bloopGlobalJsonFilePath: AbsolutePath,
      bloopCreatedByMetalsFilePath: AbsolutePath,
      requestedBloopJvmProperties: List[String],
      maybeJavaHome: Option[String]
  ): Try[Unit] = Try {

    val javaOptionsString =
      s"\"javaOptions\": [${requestedBloopJvmProperties.map(property => s"\"$property\"").mkString(", ")}]"

    val jvmPropertiesString = maybeJavaHome
      .map { javaHome =>
        s"""|{
            | $javaOptionsString,
            | \"javaHome\": \"$javaHome\"
            |}""".stripMargin
      }
      .getOrElse(s"{$javaOptionsString}")
    bloopGlobalJsonFilePath.writeText(jvmPropertiesString)
    bloopCreatedByMetalsFilePath.writeText(
      bloopGlobalJsonFilePath.toNIO.toFile.lastModified().toString
    )
  }

  private def getBloopGlobalJsonLastModifiedByMetalsTime(
      bloopCreatedByMetalsFilePath: AbsolutePath
  ): Long = Try {
    bloopCreatedByMetalsFilePath.readText.toLong
  }.getOrElse(0)

  private def processUserPreferenceForBloopJvmProperties(
      messageActionItem: MessageActionItem,
      bloopGlobalJsonFilePath: AbsolutePath,
      bloopCreatedByMetalsFilePath: AbsolutePath,
      requestedBloopJvmProperties: List[String],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange]
  ): Future[Unit] = {
    messageActionItem match {

      case item
          if item == Messages.BloopGlobalJsonFilePremodified.applyAndRestart =>
        writeJVMPropertiesToBloopGlobalJsonFile(
          bloopGlobalJsonFilePath,
          bloopCreatedByMetalsFilePath,
          requestedBloopJvmProperties,
          maybeJavaHome
        ) match {
          case Failure(exception) => Future.failed(exception)
          case Success(_) =>
            shutdownServer()
            reconnect().ignoreValue
        }

      case item
          if item == Messages.BloopGlobalJsonFilePremodified.openGlobalJsonFile =>
        val position = new Position(0, 0)
        val range = new org.eclipse.lsp4j.Range(position, position)
        val command = ClientCommands.GotoLocation.toExecuteCommandParams(
          ClientCommands.WindowLocation(
            bloopGlobalJsonFilePath.toURI.toString,
            range
          )
        )
        Future.successful(languageClient.metalsExecuteClientCommand(command))

      case item
          if item == Messages.BloopGlobalJsonFilePremodified.useGlobalFile =>
        Future.successful(())
    }
  }

  private def updateBloopGlobalJsonFileThenRestart(
      bloopGlobalJsonFilePath: AbsolutePath,
      bloopCreatedByMetalsFilePath: AbsolutePath,
      requestedBloopJvmProperties: List[String],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange]
  ): Future[Unit] = {
    writeJVMPropertiesToBloopGlobalJsonFile(
      bloopGlobalJsonFilePath,
      bloopCreatedByMetalsFilePath,
      requestedBloopJvmProperties,
      maybeJavaHome
    ) match {
      case Failure(exception) => Future.failed(exception)
      case Success(_) =>
        languageClient
          .showMessageRequest(
            Messages.BloopJvmPropertiesChange.params()
          )
          .asScala
          .flatMap {
            case messageActionItem
                if messageActionItem == Messages.BloopJvmPropertiesChange.reconnect =>
              shutdownServer()
              reconnect().ignoreValue
            case _ =>
              Future.successful(())
          }
    }

  }

  private def getBloopFilePath(fileName: String): Option[AbsolutePath] = {
    Try {
      AbsolutePath(
        Paths
          .get(System.getProperty("user.home"))
          .resolve(s".bloop/$fileName")
      )
    }.toOption
  }

  private def getBloopGlobalJsonLastModifiedTime(
      bloopGlobalJsonFilePath: AbsolutePath
  ): Long =
    Try {
      bloopGlobalJsonFilePath.toNIO.toFile.lastModified()
    }.getOrElse(0)

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
   * @param maybeRunningBloopJvmProperties
   * @param maybeJavaHome                    Metals' `javaHome`, which Bloop
   *                                         should also preferebly use
   * @param reconnect                        function to connect back to the
   *                                         build server.
   * @return `Future.successful` if the purpose is achieved or `Future.failure`
   *         if a problem occured such as lacking enough permissions to open or
   *         write to files
   */
  def ensureDesiredJvmSettings(
      maybeRequestedBloopJvmProperties: Option[List[String]],
      maybeRunningBloopJvmProperties: Option[List[String]],
      maybeJavaHome: Option[String],
      reconnect: () => Future[BuildChange]
  ): Future[Unit] = {
    val result =
      for { // if the requestedBloopJvmProperties and bloopGlobalJsonFilePath are defined
        requestedBloopJvmProperties <- maybeRequestedBloopJvmProperties
        bloopGlobalJsonFilePath <- getBloopFilePath(fileName = "bloop.json")
        bloopCreatedByMetalsFilePath <- getBloopFilePath(fileName =
          "created_by_metals.lock"
        )
      } yield
        if (
          maybeRequestedBloopJvmProperties != maybeRunningBloopJvmProperties &&
          !(requestedBloopJvmProperties.isEmpty && maybeRunningBloopJvmProperties.isEmpty)
        ) { // the properties are updated
          if (
            bloopGlobalJsonFilePath.exists &&
            getBloopGlobalJsonLastModifiedTime(
              bloopGlobalJsonFilePath
            ) > getBloopGlobalJsonLastModifiedByMetalsTime(
              bloopCreatedByMetalsFilePath
            )
          ) {
            // the global json file was previously modified by the user through other means;
            // therefore overwriting it requires user input
            languageClient
              .showMessageRequest(
                Messages.BloopGlobalJsonFilePremodified.params()
              )
              .asScala
              .flatMap {
                processUserPreferenceForBloopJvmProperties(
                  _,
                  bloopGlobalJsonFilePath,
                  bloopCreatedByMetalsFilePath,
                  requestedBloopJvmProperties,
                  maybeJavaHome,
                  reconnect
                ) andThen {
                  case Failure(exception) =>
                    languageClient.showMessage(
                      MessageType.Error,
                      exception.getMessage
                    )
                  case Success(_) => Future.successful()
                }
              }
          } else {
            // bloop global json file did not exist; or it was last modified by metals;
            // hence it can get created or overwritten by Metals with no worries
            // about overriding the user preferred settings
            updateBloopGlobalJsonFileThenRestart(
              bloopGlobalJsonFilePath,
              bloopCreatedByMetalsFilePath,
              requestedBloopJvmProperties,
              maybeJavaHome,
              reconnect
            ) andThen {
              case Failure(exception) =>
                languageClient.showMessage(
                  MessageType.Error,
                  exception.getMessage
                )
              case Success(_) => Future.successful()
            }
          }
        } else Future.successful(())

    result.getOrElse(Future.successful(()))

  }

  private def connectToLauncher(
      bloopVersion: String,
      bloopPort: Option[Int]
  ): Future[SocketConnection] = {
    val launcherInOutPipe = Pipe.open()
    val launcherIn = new QuietInputStream(
      Channels.newInputStream(launcherInOutPipe.source()),
      "Bloop InputStream"
    )
    val clientOut = new ClosableOutputStream(
      Channels.newOutputStream(launcherInOutPipe.sink()),
      "Bloop OutputStream"
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
        serverStarted
      )

    val finished = Promise[Unit]()
    val job = ec.submit(new Runnable {
      override def run(): Unit = {
        launcher.runLauncher(
          bloopVersion,
          skipBspConnection = false,
          Nil
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
            Cancelable(() => job.cancel(true))
          ),
          finished
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
}
