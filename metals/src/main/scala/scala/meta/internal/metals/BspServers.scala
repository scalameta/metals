package scala.meta.internal.metals

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Messages.BspSwitch
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.gson.Gson
import io.github.soc.directories.ProjectDirectories

/**
 * Implements BSP server discovery, named "BSP Connection Protocol" in the spec.
 *
 * See https://github.com/scalacenter/bsp/blob/master/docs/bsp.md#bsp-connection-protocol
 */
final class BspServers(
    mainWorkspace: AbsolutePath,
    charset: Charset,
    client: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    tables: Tables,
    bspGlobalInstallDirectories: List[AbsolutePath],
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService) {

  def resolve(): BspResolveResult = {
    findAvailableServers(mainWorkspace) match {
      case Nil => ResolveNone
      case head :: Nil => ResolveBspOne(head)
      case availableServers =>
        val md5 = digestServerDetails(availableServers)
        val selectedServer = for {
          name <- tables.buildServers.selectedServer(md5)
          server <- availableServers.find(_.getName == name)
        } yield server
        selectedServer match {
          case Some(details) => ResolveBspOne(details)
          case None => ResolveMultiple(md5, availableServers)
        }
    }
  }

  def newServer(
      projectDirectory: AbsolutePath
  ): Future[Option[BuildServerConnection]] = {
    def makeServer(details: BspConnectionDetails) =
      newServer(projectDirectory, details).map(Some(_))
    resolve() match {
      case ResolveBloop => Future.successful(None)
      case ResolveNone => Future.successful(None)
      case ResolveBspOne(details) => makeServer(details)
      case ResolveMultiple(md5, availableServers) =>
        askUser(md5, availableServers).flatMap(s =>
          s.map(makeServer).getOrElse(Future.successful(None))
        )
    }
  }

  /**
   * Runs "Switch build server" command, returns true if build server was changed
   */
  def switchBuildServer(): Future[Boolean] = {
    findAvailableServers(mainWorkspace) match {
      case Nil =>
        client.showMessage(BspSwitch.noInstalledServer)
        Future.successful(false)
      case head :: Nil =>
        client.showMessage(BspSwitch.onlyOneServer(head.getName))
        Future.successful(false)
      case availableServers =>
        val md5 = digestServerDetails(availableServers)
        askUser(md5, availableServers).map(_ => true)
    }
  }

  private def newServer(
      projectDirectory: AbsolutePath,
      details: BspConnectionDetails
  ): Future[BuildServerConnection] = {

    def newConnection(): Future[SocketConnection] = {
      val process = new ProcessBuilder(details.getArgv)
        .directory(projectDirectory.toFile)
        .start()

      val output = new ClosableOutputStream(
        process.getOutputStream,
        s"${details.getName} output stream"
      )
      val input = new QuietInputStream(
        process.getInputStream,
        s"${details.getName} input stream"
      )

      val finished = Promise[Unit]()
      Future {
        process.waitFor()
        finished.success(())
      }

      Future.successful {
        SocketConnection(
          details.getName(),
          output,
          input,
          List(
            Cancelable(() => process.destroy())
          ),
          finished
        )
      }
    }

    BuildServerConnection.fromSockets(
      projectDirectory,
      buildClient,
      client,
      newConnection,
      tables.dismissedNotifications.ReconnectBsp,
      config
    )
  }

  private def findAvailableServers(
      projectDirectory: AbsolutePath
  ): List[BspConnectionDetails] = {
    val jsonFiles = findJsonFiles(projectDirectory)
    val gson = new Gson()
    for {
      candidate <- jsonFiles
      text = FileIO.slurp(candidate, charset)
      details <- Try(gson.fromJson(text, classOf[BspConnectionDetails])).fold(
        e => {
          scribe.error(s"parse error: $candidate", e)
          List()
        },
        details => {
          List(details)
        }
      )
    } yield {
      details
    }
  }

  private def findJsonFiles(
      projectDirectory: AbsolutePath
  ): List[AbsolutePath] = {
    val buf = List.newBuilder[AbsolutePath]
    def visit(dir: AbsolutePath): Unit =
      dir.list.foreach { p =>
        if (p.extension == "json") {
          buf += p
        }
      }
    visit(projectDirectory.resolve(".bsp"))
    bspGlobalInstallDirectories.foreach(visit)
    buf.result()
  }

  private def askUser(
      md5: String,
      availableServers: List[BspConnectionDetails]
  ): Future[Option[BspConnectionDetails]] = {
    val query = Messages.SelectBspServer.request(availableServers)
    for {
      item <- client.showMessageRequest(query.params).asScala
    } yield {
      val chosen =
        if (item == null) {
          None
        } else {
          query.details.get(item.getTitle)
        }
      val name = chosen.fold("<none>")(_.getName)
      tables.buildServers.chooseServer(md5, name)
      scribe.info(s"selected build server: $name")
      chosen
    }
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
}
