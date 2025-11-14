package scala.meta.internal.bsp

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ImportedBuild
import scala.meta.internal.metals.TaskProgress

case class BspSession(
    main: BuildServerConnection,
    meta: List[BuildServerConnection],
)(implicit ec: ExecutionContext)
    extends Cancelable {

  val connections: List[BuildServerConnection] = main :: meta
  private val lastImported = new AtomicReference(List[BspSession.BspBuild]())

  def lastImportedBuild: Seq[ImportedBuild] = lastImported.get().map(_.build)

  def importBuilds(
      progress: TaskProgress
  ): Future[List[BspSession.BspBuild]] = {
    def importSingle(conn: BuildServerConnection): Future[BspSession.BspBuild] =
      ImportedBuild.fromConnection(conn).map(BspSession.BspBuild(conn, _))

    val imports = connections.map(conn => {
      progress.message = s"importing ${conn.name} build"
      val result = importSingle(conn)
      result.onComplete { _ =>
        progress.message = s"imported ${conn.name} build"
      }
      result
    })

    Future.sequence(imports).map { imports =>
      lastImported.set(imports)
      imports
    }
  }

  def cancel(): Unit = connections.foreach(_.cancel())

  def shutdown(): Future[Unit] =
    Future.sequence(connections.map(_.shutdown())).map(_ => ())

  def mainConnection: BuildServerConnection = main

  def mainConnectionIsBloop: Boolean = main.name == BloopServers.name

  def version: String = main.version

  def workspaceReload(): Future[List[Object]] =
    Future.sequence(connections.map(conn => conn.workspaceReload()))

  def canReloadWorkspace: Boolean = connections.forall(_.canReloadWorkspace)
}

object BspSession {
  case class BspBuild(
      connection: BuildServerConnection,
      build: ImportedBuild,
  )
}
