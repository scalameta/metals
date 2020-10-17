package scala.meta.internal.metals

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTools
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import scala.meta.internal.metals.Messages.BspSwitch
import org.eclipse.lsp4j.services.LanguageClient
import scala.meta.internal.metals.MetalsEnrichments._
import com.google.common.collect.ImmutableList

// TODO move this ADT out of here and also probably rename? BspResolvedResult
sealed trait BspResolveResult
case object ResolveNone extends BspResolveResult
case object ResolveBloop extends BspResolveResult
case class ResolveBspOne(details: BspConnectionDetails) extends BspResolveResult
case class ResolveMultiple(md5: String, details: List[BspConnectionDetails])
    extends BspResolveResult

// TODO this seems weird here, where should we move it?
object BspConnector {
  final val BLOOP_SELECTED = "BLOOP"
}

// TODO the more I mess around with all this stuff, a nice refactoring would be to
// create a BSP package, and move all the connector, discovery, management of the
// BSP related things into its own package.
class BspConnector(
    bloopServers: BloopServers,
    bspServers: BspServers,
    buildTools: BuildTools,
    client: LanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext) {

  def resolve(): BspResolveResult = {
    resolveExplicit().getOrElse {
      if (buildTools.isBloop) ResolveBloop
      else bspServers.resolve()
    }
  }

  private def resolveExplicit(): Option[BspResolveResult] = {
    tables.buildServers.selectedServer().flatMap { sel =>
      if (sel == BspConnector.BLOOP_SELECTED) Some(ResolveBloop)
      else
        bspServers
          .findAvailableServers()
          .find(_.getName == sel)
          .map(x => ResolveBspOne(x))
    }
  }

  def connect(
      workspace: AbsolutePath,
      userConfiguration: UserConfiguration
  )(implicit ec: ExecutionContext): Future[Option[BspSession]] = {
    def connect(
        workspace: AbsolutePath
    ): Future[Option[BuildServerConnection]] = {
      scribe.info("Attempting to connect to the build server...")
      resolve() match {
        case ResolveNone =>
          scribe.info("No build server found")
          Future.successful(None)
        case ResolveBloop =>
          scribe.info(
            "Attempting to start a bloop connection from previous choice"
          )
          bloopServers.newServer(workspace, userConfiguration).map(Some(_))
        case ResolveBspOne(details) =>
          pprint.log(details)
          scribe.info(
            s"Attempting to start a new connection to ${details.getName()} from previous choice..."
          )
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolveMultiple(_, _) => Future.successful(None)
      }
    }

    // TODO-BSP ensure the timing of this is correct, we don't want the metabuilds yet for sbt BSP
    // because afaik, they don't support it? So if the user has chosen bloop, but this point, there
    // will have been a .bloop directory created alraedy, and therefore we check for both to avoid
    // passing the metabuild to sbt.
    val metaDirectories =
      if (buildTools.isSbt && buildTools.isBloop) sbtMetaWorkspaces(workspace)
      else List.empty

    pprint.log(metaDirectories)
    connect(workspace).flatMap { possibleBuildServerConn =>
      possibleBuildServerConn match {
        case None => Future.successful(None)
        case Some(buildServerConn) =>
          val metaConns = metaDirectories.map(connect(_))
          Future
            .sequence(metaConns)
            .map(meta => Some(BspSession(buildServerConn, meta.flatten)))
      }
    }
  }

  private def sbtMetaWorkspaces(root: AbsolutePath): List[AbsolutePath] = {
    def recursive(
        p: AbsolutePath,
        acc: List[AbsolutePath]
    ): List[AbsolutePath] = {
      val projectDir = p.resolve("project")
      val bloopDir = projectDir.resolve(".bloop")
      if (Files.exists(bloopDir.toNIO))
        recursive(projectDir, projectDir :: acc)
      else
        acc
    }
    recursive(root, List.empty)
  }

  private def askUser(
      bspServerConnections: List[BspConnectionDetails],
      isBloop: Boolean
  ): Future[BspResolveResult] = {
    val bloop = new BspConnectionDetails(
      "bloop (default)",
      ImmutableList.of(),
      userConfig().currentBloopVersion,
      "",
      ImmutableList.of()
    )
    val availableServers =
      if (isBloop) bloop :: bspServerConnections else bspServerConnections

    val query = Messages.SelectBspServer.request(availableServers)
    for {
      item <- client.showMessageRequest(query.params).asScala
    } yield {
      val chosenMaybe = Option(item).flatMap(i => query.details.get(i.getTitle))
      val result = chosenMaybe
        .map { chosen =>
          if (chosen == bloop) {
            ResolveBloop
          } else {
            ResolveBspOne(chosen)
          }
        }
        .getOrElse(ResolveNone)
      scribe.info(s"selected build server: $chosenMaybe")
      result
    }
  }

  /**
   * Runs "Switch build server" command, returns true if build server was changed
   */
  def switchBuildServer(): Future[Boolean] = {
    val bloopPresent = buildTools.isBloop
    bspServers.findAvailableServers() match {
      case Nil =>
        if (bloopPresent)
          client.showMessage(BspSwitch.onlyOneServer(name = "bloop"))
        else
          client.showMessage(BspSwitch.noInstalledServer)
        Future.successful(false)
      case head :: Nil if !bloopPresent =>
        client.showMessage(BspSwitch.onlyOneServer(head.getName))
        Future.successful(false)
      case availableServers =>
        val currentBsp = tables.buildServers.selectedServer()
        askUser(availableServers, bloopPresent).map {
          case ResolveBloop =>
            if (currentBsp.contains(BspConnector.BLOOP_SELECTED)) false
            else {
              tables.buildServers.chooseServer(BspConnector.BLOOP_SELECTED)
              true
            }
          case ResolveBspOne(details) =>
            if (currentBsp.contains(details.getName)) false
            else {
              tables.buildServers.chooseServer(details.getName)
              true
            }
          case _ =>
            false
        }
    }
  }

}
