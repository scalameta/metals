package scala.meta.internal.bsp

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.BspSwitch
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.common.collect.ImmutableList
import org.eclipse.lsp4j.services.LanguageClient

object BspConnector {
  final val BLOOP_SELECTED = "BLOOP"
}

class BspConnector(
    bloopServers: BloopServers,
    bspServers: BspServers,
    buildTools: BuildTools,
    client: LanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration
)(implicit ec: ExecutionContext) {

  def resolve(): BspResolvedResult = {
    resolveExplicit().getOrElse {
      if (buildTools.isBloop) ResolvedBloop
      else bspServers.resolve()
    }
  }

  private def resolveExplicit(): Option[BspResolvedResult] = {
    tables.buildServers.selectedServer().flatMap { sel =>
      if (sel == BspConnector.BLOOP_SELECTED) Some(ResolvedBloop)
      else
        bspServers
          .findAvailableServers()
          .find(_.getName == sel)
          .map(x => ResolvedBspOne(x))
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
        case ResolvedNone =>
          scribe.info("No build server found")
          Future.successful(None)
        case ResolvedBloop =>
          scribe.info(
            "Attempting to start a bloop connection from previous choice"
          )
          bloopServers.newServer(workspace, userConfiguration).map(Some(_))
        case ResolvedBspOne(details)
            if details.getName() == SbtBuildTool.name =>
          scribe.info(
            s"Attempting to start a new connection to ${details.getName()} from previous choice..."
          )
          SbtBuildTool.writeSbtBspPlugin(workspace)
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolvedBspOne(details) =>
          scribe.info(
            s"Attempting to start a new connection to ${details.getName()} from previous choice..."
          )
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolvedMultiple(_, _) => Future.successful(None)
      }
    }

    connect(workspace).flatMap { possibleBuildServerConn =>
      possibleBuildServerConn match {
        case None => Future.successful(None)
        case Some(buildServerConn)
            if buildServerConn.isBloop && buildTools.isSbt =>
          // NOTE: (ckipp01) we special case this here since sbt bsp server
          // doesn't yet support metabuilds. So in the future when that
          // changes, re-work this and move the creation of this out above
          val metaConns = sbtMetaWorkspaces(workspace).map(connect(_))
          Future
            .sequence(metaConns)
            .map(meta => Some(BspSession(buildServerConn, meta.flatten)))
        case Some(buildServerConn) =>
          Future(Some(BspSession(buildServerConn, List.empty)))
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
  ): Future[BspResolvedResult] = {
    // TODO-BSP instead of showing bloop (default) it might be better
    // to show the chosen build server instead like
    // bloop (current)
    // sbt
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
            ResolvedBloop
          } else {
            ResolvedBspOne(chosen)
          }
        }
        .getOrElse(ResolvedNone)
      scribe.info(s"selected build server: $chosenMaybe")
      result
    }
  }

  /**
   * Runs "Switch build server" command, returns true if build server was changed
   */
  def switchBuildServer(workspace: AbsolutePath): Future[Boolean] = {
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
          case ResolvedBloop =>
            if (currentBsp.contains(BspConnector.BLOOP_SELECTED)) false
            else {
              tables.buildServers.chooseServer(BspConnector.BLOOP_SELECTED)
              true
            }
          case ResolvedBspOne(details)
              if !currentBsp.contains(details.getName) =>
            tables.buildServers.chooseServer(details.getName)
            true
          case _ =>
            false
        }
    }
  }

}
