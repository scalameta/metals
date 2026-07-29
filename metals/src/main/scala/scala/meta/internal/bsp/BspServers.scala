package scala.meta.internal.bsp

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.bsp.BspServers.readInBspConfig
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsProjectDirectories
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.TaskProgress
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.metals.mbt.MbtBuildServerConnectionFactory
import scala.meta.internal.metals.mbt.MbtDebugSessionStarter
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson

/**
 * Implements BSP server discovery, named "BSP Connection Protocol" in the spec.
 *
 * See https://build-server-protocol.github.io/docs/server-discovery.html
 */
final class BspServers(
    mainWorkspace: AbsolutePath,
    charset: Charset,
    client: ConfiguredLanguageClient,
    buildClient: MetalsBuildClient,
    tables: Tables,
    bspGlobalInstallDirectories: List[AbsolutePath],
    config: MetalsServerConfig,
    userConfig: () => UserConfiguration,
    mbtBuild: () => MbtBuild,
    workDoneProgress: WorkDoneProgress,
    scalaVersionSelector: ScalaVersionSelector,
    hasMbtImporters: () => Boolean = () => false,
    mbtDebugStarter: () => Option[MbtDebugSessionStarter] = () => None,
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
          name <- tables.buildServers.selectedServer()
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
      progress: TaskProgress,
  ): Future[BuildServerConnection] = {
    progress.message = s"connecting to ${details.getName()}"

    if (MbtBuildServer.isMbtServer(details.getName())) {
      val evaluateMbtBuild = mbtBuild
      val connectionFactory = new MbtBuildServerConnectionFactory(
        projectDirectory,
        buildClient,
        client,
        config,
        tables.dismissedNotifications.RequestTimeout,
        tables.dismissedNotifications.ReconnectBsp,
        bspStatusOpt,
        workDoneProgress,
        scalaVersionSelector,
        debugStarter = mbtDebugStarter(),
      ) {
        override protected def userConfiguration(): UserConfiguration =
          userConfig()
        override protected def mbtBuild(): MbtBuild = evaluateMbtBuild()
      }
      connectionFactory.fromSockets()
    } else {
      val connectionFactory = new BspServerConnectionFactory(
        projectDirectory,
        bspTraceRoot,
        buildClient,
        client,
        tables.dismissedNotifications.RequestTimeout,
        tables.dismissedNotifications.ReconnectBsp,
        config,
        details,
        bspStatusOpt,
        workDoneProgress = workDoneProgress,
        progress,
      ) {
        override protected def userConfiguration(): UserConfiguration =
          userConfig()

      }
      connectionFactory.fromSockets()
    }
  }

  /**
   * Returns a list of BspConnectionDetails from reading the .bsp/
   *  entries. Notes that this will not return Bloop even though it
   *  may be a server in the current workspace.
   *
   * MBT is included when it already has targets OR when the user has
   * configured it as preferred/selected.
   */
  def findAvailableServers(): List[BspConnectionDetails] = {
    val includeMbt =
      !mbtBuild().isEmpty ||
        hasMbtImporters() ||
        userConfig().preferredBuildServer.contains(MbtBuildServer.name) ||
        tables.buildServers.selectedServer().contains(MbtBuildServer.name)
    (findJsonFiles().flatMap(readInBspConfig(_, charset)) :::
      Option.when(includeMbt)(MbtBuildServer.details).toList)
  }

  private def findJsonFiles(): List[AbsolutePath] = {
    val buf = List.newBuilder[AbsolutePath]
    def visit(dir: AbsolutePath): Unit =
      dir.list.foreach { p =>
        if (p.isJson) {
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
  def globalInstallDirectories(implicit
      ec: ExecutionContext
  ): List[AbsolutePath] = {
    val dirs = MetalsProjectDirectories.fromPath("bsp", silent = false)
    dirs match {
      case Some(dirs) =>
        List(dirs.dataLocalDir, dirs.dataDir).distinct
          .filter(MetalsProjectDirectories.isNotBroken)
          .map(path => Try(AbsolutePath(path)).toOption)
          .flatten
      case None =>
        Nil
    }
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
        if (details.getVersion() == null) {
          val json = ujson.read(text)
          json("millVersion").strOpt.map(details.setVersion)
        }
        Some(details)
      },
    )
  }

}
