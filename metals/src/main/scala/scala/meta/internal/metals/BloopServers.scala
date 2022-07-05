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
import scala.meta.internal.bsp.ConnectionBspStatus
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
    workDoneProgress: WorkDoneProgress,
    sh: ScheduledExecutorService,
    projectRoot: () => AbsolutePath,
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
      bspTraceRoot: AbsolutePath,
      userConfiguration: UserConfiguration,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {
    val bloopVersionOpt = userConfiguration.bloopVersion
    BuildServerConnection
      .fromSockets(
        bspTraceRoot,
        projectRoot(),
        client,
        languageClient,
        () => connect(bloopVersionOpt, userConfiguration),
        tables.dismissedNotifications.ReconnectBsp,
        tables.dismissedNotifications.RequestTimeout,
        config,
        name,
        bspStatusOpt,
        workDoneProgress = workDoneProgress,
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

  private def metalsJavaHome(userConfiguration: UserConfiguration) =
    userConfiguration.javaHome
      .orElse(sys.env.get("JAVA_HOME"))
      .orElse(sys.props.get("java.home"))

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
          metalsJavaHome0,
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
