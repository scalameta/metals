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
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BspConnectionDetails
import com.google.common.collect.ImmutableList
import org.eclipse.lsp4j.services.LanguageClient

class BspConnector(
    bloopServers: BloopServers,
    bspServers: BspServers,
    buildTools: BuildTools,
    client: LanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration,
    statusBar: StatusBar
)(implicit ec: ExecutionContext) {

  def resolve(): BspResolvedResult = {
    resolveExplicit().getOrElse {
      if (buildTools.loadSupported().nonEmpty || buildTools.isBloop)
        ResolvedBloop
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
          .map(ResolvedBspOne)
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
          bloopServers.newServer(workspace, userConfiguration).map(Some(_))
        case ResolvedBspOne(details)
            if details.getName() == SbtBuildTool.name =>
          SbtBuildTool.writeSingleSbtMetalsPlugin(
            workspace.resolve("project"),
            userConfig,
            isBloop = false
          )
          val connectionF = bspServers.newServer(workspace, details)
          statusBar
            .trackFuture("Connecting to sbt", connectionF, showTimer = true)
            .map(Some(_))
        case ResolvedBspOne(details) =>
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolvedMultiple(_, availableServers) =>
          val query =
            Messages.SelectBspServer.request(availableServers, None)
          for {
            Some(item) <- client
              .showMessageRequest(query.params)
              .asScala
              .map(item => query.details.get(item.getTitle))
            conn <- bspServers.newServer(workspace, item)
          } yield Some(conn)
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
      currentBsp: Option[String]
  ): Future[BspResolvedResult] = {
    val bloop = new BspConnectionDetails(
      "Bloop",
      ImmutableList.of(),
      userConfig().currentBloopVersion,
      "",
      ImmutableList.of()
    )

    val query = Messages.SelectBspServer.request(
      bloop :: bspServerConnections,
      currentBsp
    )
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
      result
    }
  }

  /**
   * Runs "Switch build server" command, returns true if build server was changed
   */
  def switchBuildServer(
      workspace: AbsolutePath,
      createBloopAndConnect: () => Future[BuildChange]
  ): Future[Boolean] = {
    val bloopPresent = buildTools.isBloop
    bspServers.findAvailableServers() match {
      case Nil =>
        if (bloopPresent)
          client.showMessage(BspSwitch.onlyOneServer(name = "Bloop"))
        else
          client.showMessage(BspSwitch.noInstalledServer)
        Future.successful(false)
      case availableServers =>
        val currentBsp = tables.buildServers.selectedServer()
        askUser(availableServers, currentBsp).map {
          case ResolvedBloop
              if currentBsp.contains(BspConnector.BLOOP_SELECTED) =>
            false
          case ResolvedBloop =>
            tables.buildServers.chooseServer(BspConnector.BLOOP_SELECTED)
            // If a .bloop/ is already in the workspace, then we can just
            // return true for a build change and let the bsp connection be
            // made. However, if not, then we do a createBloopAndConnect()
            // instead to first generate the .bloop/ to ensure a connection can
            // be made. We return false since the the method will take care of
            // connecting after the .bloop/ dir is made
            if (bloopPresent) {
              true
            } else {
              createBloopAndConnect().ignoreValue
              false
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

object BspConnector {
  final val BLOOP_SELECTED = "BLOOP"
}
