package scala.meta.internal.metals

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import org.flywaydb.core.Flyway
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
    flyway.migrate()
    val conn = DriverManager.getConnection(url, user, null)
    new Tables(conn, time)
  }
}
