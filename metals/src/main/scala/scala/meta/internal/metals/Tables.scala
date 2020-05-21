package scala.meta.internal.metals

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import scala.meta.internal.builds.Digests
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal
import scala.meta.internal.pc.InterruptException

final class Tables(
    workspace: AbsolutePath,
    time: Time,
    clientConfig: ClientConfiguration
) extends Cancelable {
  val jarSymbols = new JarTopLevels(() => connection)
  val digests =
    new Digests(() => connection, time)
  val dependencySources =
    new DependencySources(() => connection)
  val dismissedNotifications =
    new DismissedNotifications(() => connection, time)
  val buildServers =
    new ChosenBuildServers(() => connection, time)
  val buildTool =
    new ChosenBuildTool(() => connection)

  def connect(): Unit = {
    this._connection =
      if (clientConfig.initialConfig.isAutoServer) tryAutoServer()
      else tryAutoServer()
  }
  def cancel(): Unit = connection.close()
  private var _connection: Connection = _
  private def connection: Connection = {
    if (_connection == null || _connection.isClosed) {
      connect()
    }
    _connection
  }

  // The try/catch dodge-ball court in these methods is not glamorous, I'm sure it can be refactored for more
  // readability and extensibility but it seems to get the job done for now. The most important goals are:
  // 1. Never fail to establish a connection, even if that means using an in-memory database with degraded UX.
  // 2. Log helpful error message with actionable advice on how to fix the problem.
  private def tryAutoServer(): Connection = {
    try persistentConnection(isAutoServer = true)
    catch {
      case NonFatal(e) =>
        val message =
          s"unable to setup persistent H2 database with AUTO_SERVER=true, falling back to AUTO_SERVER=false."
        e match {
          case InterruptException() =>
            scribe.info(message)
          case _ =>
            scribe.error(e)
        }
        tryNoAutoServer()
    }
  }

  private def tryNoAutoServer(): Connection = {
    try {
      persistentConnection(isAutoServer = true)
    } catch {
      case NonFatal(e) =>
        scribe.error(
          s"unable to setup persistent H2 database with AUTO_SERVER=false, falling back to in-memory database. " +
            s"This means you may be redundantly asked to execute 'Import build', even if it's not needed. " +
            s"Also, code navigation will not work for existing files in the .metals/readonly/ directory. " +
            s"To fix this problem, make sure you only have one running Metals server in the directory '$workspace'.",
          e
        )

        RecursivelyDelete(workspace.resolve(Directories.readonly))
        inMemoryConnection()
    }
  }

  private def databasePath: AbsolutePath =
    workspace.resolve(Directories.database)

  private def inMemoryConnection(): Connection = {
    tryUrl("jdbc:h2:mem:metals;DB_CLOSE_DELAY=-1")
  }

  private def persistentConnection(isAutoServer: Boolean): Connection = {
    val autoServer =
      if (isAutoServer) ";AUTO_SERVER=TRUE"
      else ""
    val dbfile = workspace.resolve(".metals").resolve("metals")
    Files.createDirectories(dbfile.toNIO.getParent)
    System.setProperty(
      "h2.bindAddress",
      System.getProperty("h2.bindAddress", "127.0.0.1")
    )
    val url = s"jdbc:h2:file:$dbfile;MV_STORE=false$autoServer"
    tryUrl(url)
  }

  private def tryUrl(url: String): Connection = {
    val user = "sa"
    val flyway = Flyway.configure.dataSource(url, user, null).load()
    migrateOrRestart(flyway)
    DriverManager.getConnection(url, user, null)
  }

  private def migrateOrRestart(
      flyway: Flyway
  ): Unit = {
    try {
      flyway.migrate()
    } catch {
      case _: FlywayException =>
        scribe.warn(s"resetting database: $databasePath")
        flyway.clean()
        flyway.migrate()
    }
  }

}
