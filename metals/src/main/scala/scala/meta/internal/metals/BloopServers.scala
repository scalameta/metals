package scala.meta.internal.metals

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.ConnectException
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ScheduledExecutorService

import scala.annotation.tailrec
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.BloopJsonUpdateCause.BloopJsonUpdateCause
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import bloop.rifle.BloopRifle
import bloop.rifle.BloopRifleConfig
import bloop.rifle.BloopRifleLogger
import bloop.rifle.BspConnection
import bloop.rifle.BspConnectionAddress
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
    sh: ScheduledExecutorService,
    workspace: () => AbsolutePath,
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
    val retCode = BloopRifle.exit(bloopConfig, workspace().toNIO, bloopLogger)
    val result = retCode == 0
    if (!result) {
      scribe.warn("There were issues stopping the Bloop server.")
      scribe.warn(
        "If it doesn't start back up you can run the `build-restart` command manually."
      )
    }
    result
  }

  def newServer(
      userConfiguration: UserConfiguration
  ): Future[BuildServerConnection] = {
    val bloopVersionOpt = userConfiguration.bloopVersion
    BuildServerConnection
      .fromSockets(
        workspace(),
        client,
        languageClient,
        () => connect(bloopVersionOpt, userConfiguration),
        tables.dismissedNotifications.ReconnectBsp,
        config,
        name,
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

  private def metalsJavaHome(userConfiguration: UserConfiguration) =
    userConfiguration.javaHome
      .orElse(sys.env.get("JAVA_HOME"))
      .orElse(sys.props.get("java.home"))

  private def updateBloopJavaHomeBeforeLaunch(
      userConfiguration: UserConfiguration
  ) = {
    val metalsJavaHome0 = metalsJavaHome(userConfiguration)
    // we should set up Java before running Bloop in order to not restart it
    bloopJsonPath match {
      case Some(bloopPath) if !bloopPath.exists =>
        metalsJavaHome0.foreach { newHome =>
          scribe.info(s"Setting up current java home $newHome in $bloopPath")
        }
        // we want to use the same java version as Metals, so it's ok to use java.home
        writeJVMPropertiesToBloopGlobalJsonFile(
          userConfiguration.bloopJvmProperties.getOrElse(Nil),
          metalsJavaHome0,
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
                  metalsJavaHome0,
                )
                metalsJavaHome0.foreach { newHome =>
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

  private lazy val bloopLogger: BloopRifleLogger = new BloopRifleLogger {
    def info(msg: => String): Unit = scribe.info(msg)
    def debug(msg: => String, ex: Throwable): Unit = scribe.debug(msg, ex)
    def error(msg: => String): Unit = scribe.error(msg)
    def error(msg: => String, ex: Throwable): Unit = scribe.error(msg, ex)

    private def loggingOutputStream(log: String => Unit): OutputStream = {
      new OutputStream {
        private val buf = new ByteArrayOutputStream
        private val lock = new Object
        private def check(): Unit = {
          lock.synchronized {
            val b = buf.toByteArray
            val s = new String(b)
            val idx = s.lastIndexOf("\n")
            if (idx >= 0) {
              s.take(idx + 1).split("\r?\n").foreach(log)
              buf.reset()
              buf.write(s.drop(idx + 1).getBytes)
            }
          }
        }
        def write(b: Int) =
          lock.synchronized {
            buf.write(b)
            if (b == '\n')
              check()
          }
        override def write(b: Array[Byte]) =
          lock.synchronized {
            buf.write(b)
            if (b.exists(_ == '\n')) check()
          }
        override def write(b: Array[Byte], off: Int, len: Int) =
          lock.synchronized {
            buf.write(b, off, len)
            if (b.iterator.drop(off).take(len).exists(_ == '\n')) check()
          }
      }
    }
    def bloopBspStdout = Some(loggingOutputStream(scribe.debug(_)))
    def bloopBspStderr = Some(loggingOutputStream(scribe.info(_)))
    def bloopCliInheritStdout = false
    def bloopCliInheritStderr = false
  }

  private lazy val bloopConfig = {

    val addr = BloopRifleConfig.Address.DomainSocket(
      config.bloopDirectory
        .orElse(getBloopFilePath("daemon"))
        .getOrElse(sys.error("user.home not set"))
        .toNIO
    )

    BloopRifleConfig
      .default(addr, fetchBloop _, workspace().toNIO.toFile)
      .copy(
        bspSocketOrPort = Some { () =>
          val pid =
            ManagementFactory.getRuntimeMXBean.getName.takeWhile(_ != '@').toInt
          val dir = getBloopFilePath("bsp")
            .getOrElse(sys.error("user.home not set"))
            .toNIO
          if (!Files.exists(dir)) {
            Files.createDirectories(dir.getParent)
            if (Properties.isWin)
              Files.createDirectory(dir)
            else
              Files.createDirectory(
                dir,
                PosixFilePermissions
                  .asFileAttribute(PosixFilePermissions.fromString("rwx------")),
              )
          }
          val socketPath = dir.resolve(s"proc-$pid")
          if (Files.exists(socketPath))
            Files.delete(socketPath)
          BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
        },
        bspStdout = bloopLogger.bloopBspStdout,
        bspStderr = bloopLogger.bloopBspStderr,
      )
  }

  private def connect(
      bloopVersionOpt: Option[String],
      userConfiguration: UserConfiguration,
  ): Future[SocketConnection] = {

    updateBloopJavaHomeBeforeLaunch(userConfiguration)

    val maybeStartBloop = {

      val running = BloopRifle.check(bloopConfig, bloopLogger)

      if (running) {
        scribe.info("Found a Bloop server running")
        Future.unit
      } else {
        scribe.info("No running Bloop server found, starting one.")
        val ext = if (Properties.isWin) ".exe" else ""
        val metalsJavaHomeOpt = metalsJavaHome(userConfiguration)
        val javaCommand = metalsJavaHomeOpt match {
          case Some(metalsJavaHome) =>
            Paths.get(metalsJavaHome).resolve(s"bin/java$ext").toString
          case None => "java"
        }
        val version = bloopVersionOpt.getOrElse(defaultBloopVersion)
        BloopRifle.startServer(
          bloopConfig,
          sh,
          bloopLogger,
          version,
          javaCommand,
        )
      }
    }

    def openConnection(
        conn: BspConnection,
        period: FiniteDuration,
        timeout: FiniteDuration,
    ): Socket = {

      @tailrec
      def create(stopAt: Long): Socket = {
        val maybeSocket =
          try Right(conn.openSocket(period, timeout))
          catch {
            case e: ConnectException => Left(e)
          }
        maybeSocket match {
          case Right(socket) => socket
          case Left(e) =>
            if (System.currentTimeMillis() >= stopAt)
              throw new IOException(s"Can't connect to ${conn.address}", e)
            else {
              Thread.sleep(period.toMillis)
              create(stopAt)
            }
        }
      }

      create(System.currentTimeMillis() + timeout.toMillis)
    }

    def openBspConn = Future {

      val conn = BloopRifle.bsp(
        bloopConfig,
        workspace().toNIO,
        bloopLogger,
      )

      val finished = Promise[Unit]()
      conn.closed.ignoreValue.onComplete(finished.tryComplete)

      val socket = openConnection(conn, bloopConfig.period, bloopConfig.timeout)

      SocketConnection(
        name,
        new ClosableOutputStream(socket.getOutputStream, "Bloop OutputStream"),
        new QuietInputStream(socket.getInputStream, "Bloop InputStream"),
        Nil,
        finished,
      )
    }

    for {
      _ <- maybeStartBloop
      conn <- openBspConn
    } yield conn
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

  def fetchBloop(version: String): Either[Throwable, (Seq[File], Boolean)] = {

    val (org, name) = BloopRifleConfig.defaultModule.split(":", -1) match {
      case Array(org0, name0) => (org0, name0)
      case Array(org0, "", name0) =>
        val sbv =
          if (BloopRifleConfig.defaultScalaVersion.startsWith("2."))
            BloopRifleConfig.defaultScalaVersion
              .split('.')
              .take(2)
              .mkString(".")
          else
            BloopRifleConfig.defaultScalaVersion.split('.').head
        (org0, name0 + "_" + sbv)
      case _ =>
        sys.error(
          s"Malformed default Bloop module '${BloopRifleConfig.defaultModule}'"
        )
    }

    try {
      val cp = coursierapi.Fetch
        .create()
        .addDependencies(coursierapi.Dependency.of(org, name, version))
        .fetch()
        .asScala
        .toVector
      val isScalaCliBloop = true
      Right((cp, isScalaCliBloop))
    } catch {
      case NonFatal(t) =>
        Left(t)
    }
  }

  def defaultBloopVersion = BloopRifleConfig.defaultVersion
}
