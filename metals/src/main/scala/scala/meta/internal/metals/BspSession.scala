package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

case class BspSession(
    main: BuildServerConnection,
    meta: List[BuildServerConnection]
)(implicit ec: ExecutionContext)
    extends Cancelable {

  val connections: List[BuildServerConnection] = main :: meta

  def importBuilds(): Future[List[BspSession.BspBuild]] = {
    def importSingle(conn: BuildServerConnection): Future[BspSession.BspBuild] =
      MetalsLanguageServer.importedBuild(conn).map(BspSession.BspBuild(conn, _))

    Future.sequence(connections.map(importSingle))
  }

  def cancel(): Unit = connections.foreach(_.cancel())

  def shutdown(): Future[Unit] =
    Future.sequence(connections.map(_.shutdown())).map(_ => ())

  def mainConnection: BuildServerConnection = main

  def version: String = main.version
}

object BspSession {

  case class BspBuild(
      connection: BuildServerConnection,
      build: ImportedBuild
  )

}
