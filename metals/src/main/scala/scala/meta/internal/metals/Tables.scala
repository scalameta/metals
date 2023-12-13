package scala.meta.internal.metals

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import scala.meta.internal.builds.Digests
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVStoreException
import org.h2.tools.Upgrade

final class Tables(
    workspace: AbsolutePath,
    time: Time,
) extends Cancelable {

  import Tables.ConnectionState

  val jarSymbols = new JarTopLevels(() => connection)
  val digests =
    new Digests(() => connection, time)
  val dependencySources =
    new DependencySources(() => connection)
  val worksheetSources =
    new WorksheetDependencySources(() => connection)
  val dismissedNotifications =
    new DismissedNotifications(() => connection, time)
  val buildServers =
    new ChosenBuildServers(() => connection, time)
  val buildTool =
    new ChosenBuildTool(() => connection)
  val fingerprints =
    new Fingerprints(() => connection)

  private val ref: AtomicReference[ConnectionState] =
    new AtomicReference(ConnectionState.Empty)

  private val user = "sa"

  def connect(): Connection = {
    ref.get() match {
      case empty @ ConnectionState.Empty =>
        if (ref.compareAndSet(empty, ConnectionState.InProgress)) {
          val conn = tryAutoServer()
          ref.set(ConnectionState.Connected(conn))
          conn
        } else
          connect()
      case Tables.ConnectionState.InProgress =>
        Thread.sleep(100)
        connect()
      case Tables.ConnectionState.Connected(conn) =>
        conn
    }
  }

  def cancel(): Unit = {
    ref.get() match {
      case v @ ConnectionState.Connected(conn) =>
        if (ref.compareAndSet(v, ConnectionState.Empty)) {
          conn.close()
        }
      case ConnectionState.InProgress =>
        Thread.sleep(100)
        cancel()
      case _ =>
    }
  }

  def databaseExists(): Boolean =
    databasePath.exists

  private def connection: Connection = connect()

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
          e,
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
    // from "h2" % "2.0.206" the only option is the MVStore, which uses `metals.mv.db` file
    val oldDbfile = workspace.resolve(".metals").resolve("metals.h2.db")
    if (oldDbfile.exists) {
      scribe.info(s"Deleting old database format $oldDbfile")
      oldDbfile.delete()
    }
    Files.createDirectories(dbfile.toNIO.getParent)
    System.setProperty(
      "h2.bindAddress",
      System.getProperty("h2.bindAddress", "127.0.0.1"),
    )
    val url = s"jdbc:h2:file:$dbfile$autoServer"
    upgradeIfNeeded(url)
    tryUrl(url)
  }

  private def tryUrl(url: String): Connection = {
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

  /**
   * Between h2 "2.1.x" and "2.2.x" write/read formats in MVStore changed
   * (https://github.com/h2database/h2database/pull/3834)
   */
  private def upgradeIfNeeded(url: String): Unit = {
    val oldVersion = 214
    val formatVersionChangedMessage =
      "The write format 2 is smaller than the supported format 3"
    try {
      DriverManager.getConnection(url, user, null).close()
    } catch {
      case e: SQLException if e.getErrorCode() == 90048 =>
        e.getCause() match {
          case e: MVStoreException
              if e.getErrorCode() == DataUtils.ERROR_UNSUPPORTED_FORMAT &&
                e.getMessage().startsWith(formatVersionChangedMessage) =>
            val info: Properties = new Properties()
            info.put("user", user)
            try {
              val didUpgrade = Upgrade.upgrade(url, info, oldVersion)
              if (didUpgrade) scribe.info(s"Upgraded H2 database.")
              else deleteDatabase()
            } catch {
              case NonFatal(_) => deleteDatabase()
            }

          case e => throw e
        }
    }
  }

  private def deleteDatabase() = {
    val dbFile = workspace.resolve(".metals").resolve("metals.mv.db")
    if (dbFile.exists) {
      scribe.warn(
        s"Deleting old database, due to failed database upgrade. Non-default build tool and build server choices will be lost."
      )
      dbFile.delete()
    }
  }

  def cleanAll(): Unit = {
    try {
      jarSymbols.clearAll()
      digests.clearAll()
      dependencySources.clearAll()
      worksheetSources.clearAll()
      fingerprints.clearAll()
    } catch {
      case NonFatal(e) =>
        scribe.error(s"failed to clean database: $databasePath", e)
    }
  }

}

object Tables {

  sealed trait ConnectionState
  object ConnectionState {
    case object Empty extends ConnectionState
    case object InProgress extends ConnectionState
    final case class Connected(conn: Connection) extends ConnectionState
  }
}
