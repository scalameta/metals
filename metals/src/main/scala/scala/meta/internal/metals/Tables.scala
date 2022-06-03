package scala.meta.internal.metals

import java.sql.Connection

import scala.util.control.NonFatal

import scala.meta.internal.builds.Digests
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

final class Tables(
    workspace: AbsolutePath,
    time: Time,
) extends H2ConnectionProvider(
      directory = workspace.resolve(".metals"),
      name = "metals",
      migrations = "/db/migration",
    ) {

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

  override protected def tryNoAutoServer(): Connection = {
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
