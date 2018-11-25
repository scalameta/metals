package scala.meta.internal.metals

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import scala.meta.io.AbsolutePath

final class Tables(connection: Connection, time: Time) extends Cancelable {
  val sbtDigests = new SbtDigests(connection, time)
  val dependencySources = new DependencySources(connection)
  val dismissedNotifications = new DismissedNotifications(connection, time)
  def cancel(): Unit = connection.close()
}

object Tables {
  def forWorkspace(workspace: AbsolutePath, time: Time): Tables = {
    val dbfile = workspace.resolve(".metals").resolve("metals")
    Files.createDirectories(dbfile.toNIO.getParent)
    val url = s"jdbc:h2:file:$dbfile;MV_STORE=false;AUTO_SERVER=true"
    val user = "sa"
    val flyway = Flyway.configure.dataSource(url, user, null).load
    migrateOrRestart(flyway, dbfile.resolveSibling(_ + ".h2.db"))
    val conn = DriverManager.getConnection(url, user, null)
    new Tables(conn, time)
  }

  private def migrateOrRestart(flyway: Flyway, path: AbsolutePath): Unit = {
    try {
      flyway.migrate()
    } catch {
      case _: FlywayException if path.isFile =>
        scribe.warn(s"Resetting Metals database $path")
        flyway.clean()
        flyway.migrate()
    }
  }

}
