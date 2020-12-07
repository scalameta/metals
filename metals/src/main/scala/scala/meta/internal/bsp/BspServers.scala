package scala.meta.internal.bsp

import java.nio.charset.Charset

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Try

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.QuietInputStream
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.Tables
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
    config: MetalsServerConfig
)(implicit ec: ExecutionContextExecutorService) {

  def newServer(
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
      config,
      details.getName()
    )
  }

  def findAvailableServers(): List[BspConnectionDetails] = {
    val jsonFiles = findJsonFiles(mainWorkspace)
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
}

object BspServers {
  def globalInstallDirectories: List[AbsolutePath] = {
    val dirs = ProjectDirectories.fromPath("bsp")
    List(dirs.dataLocalDir, dirs.dataDir).distinct
      .map(path => Try(AbsolutePath(path)).toOption)
      .flatten
  }
}
