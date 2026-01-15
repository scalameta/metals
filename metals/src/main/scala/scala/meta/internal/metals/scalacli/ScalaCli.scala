package scala.meta.internal.metals.scalacli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BuildChange
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.ImportedBuild
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TargetData
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.scalacli.ScalaCli.ScalaCliCommand
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import coursier.core.Version

// todo https://github.com/scalameta/metals/issues/4788
// clean () =>, use plain values
class ScalaCli(
    compilers: () => Compilers,
    compilations: Compilations,
    workDoneProgress: WorkDoneProgress,
    buffers: Buffers,
    indexWorkspace: () => Future[Unit],
    diagnostics: () => Diagnostics,
    tables: Tables,
    buildClient: () => MetalsBuildClient,
    languageClient: MetalsLanguageClient,
    config: () => MetalsServerConfig,
    cliCommand: ScalaCliCommand,
    parseTreesAndPublishDiags: Seq[AbsolutePath] => Future[Unit],
    val path: AbsolutePath,
    val customWorkspace: Option[AbsolutePath],
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {

  import ScalaCli.ConnectionState

  private val isCancelled = new AtomicBoolean(false)
  def cancel(): Unit =
    if (isCancelled.compareAndSet(false, true))
      try {
        disconnectOldBuildServer().asJava.get(100, TimeUnit.MILLISECONDS)
      } catch {
        case NonFatal(_) =>
        case _: TimeoutException =>
      }

  private val state =
    new AtomicReference[ConnectionState](ConnectionState.Empty)

  private def ifConnectedOrElse[A](
      f: ConnectionState.Connected => A
  )(orElse: => A): A =
    state.get() match {
      case conn: ConnectionState.Connected => f(conn)
      case _ => orElse
    }

  private def allSources(): Seq[AbsolutePath] = {
    ifConnectedOrElse { st =>
      val sourceItems = st.importedBuild.sources.getItems.asScala.toVector
      val sources = sourceItems.flatMap(_.getSources.asScala.map(_.getUri))
      val roots = sourceItems
        .flatMap(item => Option(item.getRoots).toSeq)
        .flatMap(_.asScala)
      val out: Seq[AbsolutePath] = (sources ++ roots)
        .map(new URI(_))
        .map(AbsolutePath.fromAbsoluteUri(_))
      out
    }(Seq.empty)
  }

  val buildTargetsData = new TargetData

  def lastImportedBuild: ImportedBuild =
    ifConnectedOrElse(_.importedBuild)(ImportedBuild.empty)

  def buildServer: Option[BuildServerConnection] =
    ifConnectedOrElse(conn => Option(conn.connection))(None)

  def importBuild(): Future[Unit] =
    ifConnectedOrElse { st =>
      compilers().cancel()

      workDoneProgress
        .trackFuture(
          "Importing Scala CLI sources",
          ImportedBuild.fromConnection(st.connection),
        )
        .flatMap { build0 =>
          if (state.compareAndSet(st, st.copy(importedBuild = build0))) {
            val targets = build0.workspaceBuildTargets.getTargets.asScala
            val connections =
              targets.iterator.map(_.getId).map((_, st.connection)).toList
            buildTargetsData.resetConnections(connections)

            for {
              _ <- indexWorkspace()
              allSources0 = allSources()
              toCompile = buffers.open.toSeq.filter(p =>
                allSources0
                  .exists(root => root == p || p.toNIO.startsWith(root.toNIO))
              )
              _ <- Future.sequence(
                compilations
                  .cascadeCompileFiles(toCompile) ::
                  compilers().load(toCompile) ::
                  parseTreesAndPublishDiags(allSources0) ::
                  Nil
              )
            } yield ()
          } else importBuild()
        }
    }(Future.failed(new Exception("No Scala CLI server running")))

  private def disconnectOldBuildServer(): Future[Unit] = {
    state.get() match {
      case ConnectionState.Empty => Future.unit
      case _: ConnectionState.Connecting =>
        Thread.sleep(100)
        disconnectOldBuildServer()
      case st: ConnectionState.Connected =>
        state.compareAndSet(st, ConnectionState.Empty)
        diagnostics().reset(allSources())
        st.connection.shutdown()
    }
  }

  def loaded(path: AbsolutePath): Boolean =
    ifConnectedOrElse(st =>
      st.path == path || path.toNIO.startsWith(st.path.toNIO)
    )(false)

  def start(): Future[Unit] = {

    disconnectOldBuildServer().onComplete {
      case Failure(e) =>
        scribe.warn("Error disconnecting old Scala CLI server", e)
      case Success(()) =>
    }

    val command =
      customWorkspace match {
        case Some(workspace) =>
          val sourceRootPart =
            if (cliCommand.requireMinimumVersion(ScalaCli.minSourceRootVersion))
              List("--semantic-db-source-root", path.toNIO.getParent.toString)
            else Nil
          (cliCommand.command :+ "bsp" :+ "--workspace" :+ workspace
            .toString()) ++ sourceRootPart :+ path
            .toString()
        case None => cliCommand.command :+ "bsp" :+ path.toString()
      }

    val connDir = if (path.isDirectory) path else path.parent

    val nextSt = ConnectionState.Connecting(path)
    if (state.compareAndSet(ConnectionState.Empty, nextSt)) {
      val futureConn = BuildServerConnection.fromSockets(
        connDir,
        connDir,
        buildClient(),
        languageClient,
        () => ScalaCli.socketConn(command, connDir),
        tables.dismissedNotifications.ReconnectScalaCli,
        tables.dismissedNotifications.RequestTimeout,
        config(),
        "Scala CLI",
        supportsWrappedSources = Some(true),
        workDoneProgress = workDoneProgress,
      )

      val f = futureConn.flatMap { conn =>
        state.set(ConnectionState.Connected(path, conn, ImportedBuild.empty))
        scribe.info(s"Connected to Scala CLI server v${conn.version}")
        importBuild().map { _ =>
          BuildChange.Reconnected
        }
      }

      f.transform {
        case Failure(ex) =>
          scribe.error("Error starting Scala CLI", ex)
          Success(())
        case Success(_) =>
          scribe.info(s"Scala CLI started for $path")
          Success(())
      }
    } else {
      scribe.error(s"Multiply Scala CLI start calls. Failed for: $path")
      Future.unit
    }
  }

  def stop(): Future[Unit] = disconnectOldBuildServer()

}

object ScalaCli {
  val minVersion = "0.1.3"
  val minSourceRootVersion = "1.3.2"

  private def socketConn(
      command: Seq[String],
      workspace: AbsolutePath,
  )(implicit ec: ExecutionContext): Future[SocketConnection] =
    Future {
      scribe.info(s"Running $command")
      val proc = SystemProcess.run(
        command.toList,
        workspace,
        redirectErrorOutput = false,
        env = Map("SCALA_CLI_POWER" -> "true"),
        processOut = None,
        processErr = Some(line => scribe.info("Scala CLI: " + line)),
        discardInput = false,
        threadNamePrefix = "scala-cli",
      )
      val finished = Promise[Unit]()
      proc.complete.ignoreValue.onComplete { res =>
        finished.tryComplete(res)
      }
      SocketConnection(
        ScalaCli.names.head,
        new ClosableOutputStream(proc.outputStream, "Scala CLI error stream"),
        proc.inputStream,
        List(Cancelable { () => proc.cancel }),
        finished,
      )
    }

  lazy val javaCommand: String = {

    val defaultJvmId = "temurin:17"

    val majorVersion = sys.props
      .get("java.version")
      .map(_.takeWhile(_.isDigit))
      .filter(_.nonEmpty)
      .flatMap(_.toIntOption)
      .getOrElse(0)

    val ext = if (Properties.isWin) ".exe" else ""
    if (majorVersion >= 17)
      Paths
        .get(sys.props("java.home"))
        .resolve(s"bin/java$ext")
        .toAbsolutePath
        .toString
    else {
      scribe.info(
        s"Found Java version $majorVersion. " +
          s"Scala CLI requires at least Java 17, getting a $defaultJvmId JVM via coursier-jvm."
      )
      val jvmManager = coursierapi.JvmManager.create()
      val javaHome = jvmManager.get(defaultJvmId)
      new File(javaHome, s"bin/java$ext").getAbsolutePath
    }
  }

  def scalaCliClassPath(): Seq[String] =
    coursierapi.Fetch
      .create()
      .addDependencies(
        coursierapi.Dependency
          .of("org.virtuslab.scala-cli", "cli_3", BuildInfo.scalaCliVersion)
      )
      .fetch()
      .asScala
      .toSeq
      .map(_.getAbsolutePath)

  def scalaCliMainClass: String =
    "scala.cli.ScalaCli"

  val names: Set[String] = Set("scala-cli", "scala")

  sealed trait ConnectionState
  object ConnectionState {
    case object Empty extends ConnectionState
    case class Connecting(path: AbsolutePath) extends ConnectionState
    case class Connected(
        path: AbsolutePath,
        connection: BuildServerConnection,
        importedBuild: ImportedBuild,
    ) extends ConnectionState
  }

  def localScalaCli(userConfig: UserConfiguration): Option[ScalaCliCommand] = {

    def endsWithCaseInsensitive(s: String, suffix: String): Boolean =
      s.length >= suffix.length &&
        s.regionMatches(
          true,
          s.length - suffix.length,
          suffix,
          0,
          suffix.length,
        )

    def findInPath(app: String): Option[Path] = {
      val asIs = Paths.get(app)
      if (Paths.get(app).getNameCount >= 2) Some(asIs)
      else {
        def pathEntries =
          Option(System.getenv("PATH")).iterator
            .flatMap(_.split(File.pathSeparator).iterator)
        def pathExts =
          if (Properties.isWin)
            Option(System.getenv("PATHEXT")).iterator
              .flatMap(_.split(File.pathSeparator).iterator)
          else Iterator("")
        def matches = for {
          dir <- pathEntries
          ext <- pathExts
          app0 = if (endsWithCaseInsensitive(app, ext)) app else app + ext
          path = Paths.get(dir).resolve(app0)
          if Files.isExecutable(path)
        } yield path
        matches.toStream.headOption
      }
    }

    def readFully(is: InputStream): Array[Byte] = {
      val b = new ByteArrayOutputStream
      val buf = Array.ofDim[Byte](64)
      var read = -1
      while ({
        read = is.read(buf)
        read >= 0
      })
        if (read > 0)
          b.write(buf, 0, read)
      b.toByteArray
    }

    def withVersion(executable: String): Option[ScalaCliCommand] = {
      // "scala-cli version" simply prints its version
      val process =
        new ProcessBuilder(executable, "version")
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .redirectOutput(ProcessBuilder.Redirect.PIPE)
          .redirectInput(ProcessBuilder.Redirect.PIPE)
          .start()

      val b = readFully(process.getInputStream())
      raw"\d+\.\d+\.\d+".r
        .findFirstIn(new String(b, "UTF-8"))
        .map(v => ScalaCliCommand(Seq(executable), Version(v)))
    }

    userConfig.scalaCliLauncher
      .filter(_.trim.nonEmpty)
      .flatMap(withVersion(_))
      .orElse {
        findInPath("scala-cli")
          .orElse(findInPath("scala"))
          .collect { scalaCli =>
            withVersion(scalaCli.toAbsolutePath.toString) match {
              case Some(cmd: ScalaCliCommand)
                  if cmd.requireMinimumVersion(minVersion) =>
                cmd
            }
          }
      }
  }

  val scalaCliBspVersion = "2.1.0-M4"

  def scalaCliBspJsonContent(
      args: List[String] = Nil,
      projectRoot: String = ".",
      bspName: String = "scala-cli",
  ): String = {
    val argv = List(
      ScalaCli.javaCommand,
      "-cp",
      ScalaCli.scalaCliClassPath().mkString(File.pathSeparator),
      ScalaCli.scalaCliMainClass,
      "bsp",
      projectRoot,
    ) ++ args
    val bsjJson = ujson.Obj(
      "name" -> bspName,
      "argv" -> argv,
      "version" -> BuildInfo.scalaCliVersion,
      "bspVersion" -> scalaCliBspVersion,
      "languages" -> List("scala", "java"),
    )
    ujson.write(bsjJson)
  }

  case class ScalaCliCommand(command: Seq[String], version: Version) {
    def requireMinimumVersion(minVersion: String): Boolean =
      Version(minVersion).compareTo(version) <= 0
  }
}
