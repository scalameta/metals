package scala.meta.internal.bsp

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtServer
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
    sbtServer: SbtServer,
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
        case ResolvedBspOne(details) if details.getName == SbtBuildTool.name =>
          scribe.info(
            s"Attempting to start a new connection to ${details.getName()} from previous choice..."
          )
          // NOTE: we explicity start another sbt process here as simply using newServer
          // here wil indeed start sbt, but not correctly star the bsp sessions. So before
          // we start the session we ensure that sbt is running, and then connect.
          val (_, processHandler) = sbtServer.runSbtShell()
          processHandler.initialized.future.flatMap { _ =>
            scribe.info(
              s"sbt up and running, attempting to start a bsp session..."
            )
            bspServers.newServer(workspace, details).map(Some(_))
          }
        case ResolvedBspOne(details) =>
          scribe.info(
            s"Attempting to start a new connection to ${details.getName()} from previous choice..."
          )
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolvedMultiple(_, _) => Future.successful(None)
      }
    }

    // TODO-BSP ensure the timing of this is correct, we don't want the metabuilds yet for sbt BSP
    // because afaik, they don't support it? So if the user has chosen bloop, but this point, there
    // will have been a .bloop directory created alraedy, and therefore we check for both to avoid
    // passing the metabuild to sbt.
    val metaDirectories =
      if (buildTools.isSbt && buildTools.isBloop) sbtMetaWorkspaces(workspace)
      else List.empty

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
  ): Future[BspResolvedResult] = {
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
          case ResolvedBloop =>
            if (currentBsp.contains(BspConnector.BLOOP_SELECTED)) false
            else {
              tables.buildServers.chooseServer(BspConnector.BLOOP_SELECTED)
              true
            }
          case ResolvedBspOne(details) =>
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
