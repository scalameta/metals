package scala.meta.internal.metals

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables.ConnectionState
import scala.meta.internal.pc.InterruptException
import scala.meta.io.AbsolutePath

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.h2.mvstore.DataUtils
import org.h2.mvstore.MVStoreException
import org.h2.tools.Upgrade

abstract class H2ConnectionProvider(
    directory: => AbsolutePath,
    name: String,
    migrations: String,
) extends Cancelable {

  protected val ref: AtomicReference[ConnectionState] =
    new AtomicReference(ConnectionState.Empty)
  private val user = "sa"

  protected def connection: Connection = connect()

  protected def optDirectory: Option[AbsolutePath] = Try(directory).toOption
  protected def databasePath: Option[AbsolutePath] =
    optDirectory.map(_.resolve("metals.h2.db"))

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

  protected def tryNoAutoServer(): Connection = {
    try {
      persistentConnection(isAutoServer = false)
    } catch {
      case NonFatal(e) =>
        scribe.error(e)
        inMemoryConnection()
    }
  }

  protected def inMemoryConnection(): Connection = {
    tryUrl(s"jdbc:h2:mem:${name};DB_CLOSE_DELAY=-1")
  }

  protected def persistentConnection(isAutoServer: Boolean): Connection = {
    val autoServer =
      if (isAutoServer) ";AUTO_SERVER=TRUE"
      else ""
    val dbfile = directory.resolve("metals")
    // from "h2" % "2.0.206" the only option is the MVStore, which uses `metals.mv.db` file
    val oldDbfile = directory.resolve("metals.h2.db")
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
    val flyway =
      Flyway.configure
        .dataSource(url, user, null)
        .locations(s"classpath:$migrations")
        .cleanDisabled(false)
        .load()
    migrateOrRestart(flyway)
    DriverManager.getConnection(url, user, null)
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

  private def deleteDatabase() =
    optDirectory.foreach { directory =>
      val dbFile = directory.resolve("metals.mv.db")
      if (dbFile.exists) {
        scribe.warn(
          s"Deleting old database, due to failed database upgrade. Non-default build tool and build server choices will be lost."
        )
        dbFile.delete()
      }
    }

  def databaseExists(): Boolean =
    databasePath.exists(_.exists)

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

}
