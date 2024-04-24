package scala.meta.internal.bsp

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Properties
import scala.util.Try

import scala.meta.internal.bsp.BspServers.readInBspConfig
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.URIEncoderDecoder
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import dev.dirs.ProjectDirectories

/**
 * Implements BSP server discovery, named "BSP Connection Protocol" in the spec.
 *
 * See https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BspServers(
    mainWorkspace: AbsolutePath,
    charset: Charset,
    client: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    tables: Tables,
    bspGlobalInstallDirectories: List[AbsolutePath],
    config: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    workDoneProgress: WorkDoneProgress,
)(implicit ec: ExecutionContextExecutorService) {
  private def customProjectRoot =
    userConfig().getCustomProjectRoot(mainWorkspace)

  def resolve(): BspResolvedResult = {
    findAvailableServers() match {
      case Nil => ResolvedNone
      case head :: Nil => ResolvedBspOne(head)
      case availableServers =>
        val md5 = digestServerDetails(availableServers)
        val selectedServer = for {
          name <- tables.buildServers.selectedServer(md5)
          server <- availableServers.find(_.getName == name)
        } yield server
        selectedServer match {
          case Some(details) => ResolvedBspOne(details)
          case None => ResolvedMultiple(md5, availableServers)
        }
    }
  }

  def newServer(
      projectDirectory: AbsolutePath,
      bspTraceRoot: AbsolutePath,
      details: BspConnectionDetails,
      bspStatusOpt: Option[ConnectionBspStatus],
  ): Future[BuildServerConnection] = {

    def newConnection(): Future[SocketConnection] = {

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

      scribe.info(s"Running BSP server $args")
      val proc = SystemProcess.run(
        args,
        projectDirectory,
        redirectErrorOutput = false,
        JdkSources.envVariables(userConfig().javaHome),
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

    BuildServerConnection.fromSockets(
      projectDirectory,
      bspTraceRoot,
      buildClient,
      client,
      newConnection,
      tables.dismissedNotifications.ReconnectBsp,
      tables.dismissedNotifications.RequestTimeout,
      config,
      details.getName(),
      bspStatusOpt,
      workDoneProgress = workDoneProgress,
    )
  }

  /**
   * Returns a list of BspConnectionDetails from reading the .bsp/
   *  entries. Notes that this will not return Bloop even though it
   *  may be a server in the current workspace
   */
  def findAvailableServers(): List[BspConnectionDetails] =
    findJsonFiles().flatMap(readInBspConfig(_, charset))

  private def findJsonFiles(): List[AbsolutePath] = {
    val buf = List.newBuilder[AbsolutePath]
    def visit(dir: AbsolutePath): Unit =
      dir.list.foreach { p =>
        if (p.extension == "json") {
          buf += p
        }
      }
    visit(mainWorkspace.resolve(Directories.bsp))
    customProjectRoot.map(_.resolve(Directories.bsp)).foreach(visit)
    bspGlobalInstallDirectories.foreach(visit)
    buf.result()
  }

  private def digestServerDetails(
      candidates: List[BspConnectionDetails]
  ): String = {
    val md5 = MessageDigest.getInstance("MD5")
    candidates.foreach { details =>
      md5.update(details.getName.getBytes(StandardCharsets.UTF_8))
    }
    MD5.bytesToHex(md5.digest())
  }

}

object BspServers {
  def globalInstallDirectories: List[AbsolutePath] = {
    val dirs = ProjectDirectories.fromPath("bsp")
    List(dirs.dataLocalDir, dirs.dataDir).distinct
      .map(path => Try(AbsolutePath(path)).toOption)
      .flatten
  }

  def readInBspConfig(
      path: AbsolutePath,
      charset: Charset,
  ): Option[BspConnectionDetails] = {
    val text = FileIO.slurp(path, charset)
    val gson = new Gson()
    Try(gson.fromJson(text, classOf[BspConnectionDetails])).fold(
      e => {
        scribe.error(s"parse error: $path", e)
        None
      },
      details => {
        Some(details)
      },
    )
  }

}
