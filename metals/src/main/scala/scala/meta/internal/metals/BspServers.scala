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
    workspace: AbsolutePath,
    charset: Charset,
    client: MetalsLanguageClient,
    buildClient: MetalsBuildClient,
    tables: Tables,
    bspGlobalInstallDirectories: List[AbsolutePath],
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService) {

  def newServer(): Future[Option[BuildServerConnection]] = {
    findServer().flatMap { details =>
      details
        .map(d => newServer(d).map(Option(_)))
        .getOrElse(Future.successful(None))
    }
  }

  /**
   * Runs "Switch build server" command, returns true if build server was changed */
  def switchBuildServer(): Future[Boolean] = {
    findAvailableServers() match {
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
      details: BspConnectionDetails
  ): Future[BuildServerConnection] = {

    def newConnection(): Future[SocketConnection] = {
      val process = new ProcessBuilder(details.getArgv)
        .directory(workspace.toFile)
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
      workspace,
      buildClient,
      client,
      newConnection,
      tables.dismissedNotifications.ReconnectBsp,
      config
    )
  }

  private def findServer(): Future[Option[BspConnectionDetails]] = {
    findAvailableServers() match {
      case Nil =>
        Future.successful(None)
      case head :: Nil =>
        Future.successful(Some(head))
      case availableServers =>
        val md5 = digestServerDetails(availableServers)
        val selectedServer = for {
          name <- tables.buildServers.selectedServer(md5)
          server <- availableServers.find(_.getName == name)
        } yield server
        selectedServer match {
          case Some(value) =>
            scribe.info(
              s"pre-selected build server: ${value.getName} (run 'Switch build server' command to pick a new server)"
            )
            Future.successful(Some(value))
          case None =>
            askUser(md5, availableServers)
        }
    }
  }

  private def findAvailableServers(): List[BspConnectionDetails] = {
    val jsonFiles = findJsonFiles()
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

  private def findJsonFiles(): List[AbsolutePath] = {
    val buf = List.newBuilder[AbsolutePath]
    def visit(dir: AbsolutePath): Unit =
      dir.list.foreach { p =>
        if (p.extension == "json") {
          buf += p
        }
      }
    visit(workspace.resolve(".bsp"))
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
