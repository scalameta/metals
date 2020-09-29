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


sealed trait BspResolveResult
case object ResolveNone extends BspResolveResult
case object ResolveBloop extends BspResolveResult
case class ResolveBspOne(details: BspConnectionDetails) extends BspResolveResult
case class ResolveMultiple(md5: String, details: List[BspConnectionDetails])
    extends BspResolveResult

object BspConnector {
  final val BLOOP_SELECTED = "BLOOP"
}

class BspConnector(
    bloopServers: BloopServers,
    bspServers: BspServers,
    buildTools: BuildTools,
    client: LanguageClient,
    tables: Tables,
    userConfig: () => UserConfiguration,
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
      else bspServers.findAvailableServers().find(_.getName == sel).map(x => ResolveBspOne(x))
    }
  }

  def connect(
      workspace: AbsolutePath,
      userConfiguration: UserConfiguration,
  )(implicit ec: ExecutionContext): Future[Option[BspSession]] = {
    def connect(
        workspace: AbsolutePath
    ): Future[Option[BuildServerConnection]] = {
      resolve() match {
        case ResolveNone => Future.successful(None)
        case ResolveBloop => bloopServers.newServer(workspace, userConfiguration).map(Some(_))
        case ResolveBspOne(details) => bspServers.newServer(workspace, details).map(Some(_))
        case ResolveMultiple(_, _) => Future.successful(None)
      }
    }

    val metaDirectories =
      if (buildTools.isSbt) sbtMetaWorkspaces(workspace) else List.empty

    connect(workspace).flatMap { mainOpt =>
      mainOpt match {
        case None => Future.successful(None)
        case Some(main) =>
          val metaConns = metaDirectories.map(connect(_))
          Future
            .sequence(metaConns)
            .map(meta => Some(BspSession(main, meta.flatten)))
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
      isBloop: Boolean,
  ): Future[BspResolveResult] = {
    val bloop = new BspConnectionDetails("bloop (default)", ImmutableList.of(), userConfig().currentBloopVersion, "", ImmutableList.of())
    val availableServers =
      if (isBloop) bloop :: bspServerConnections else bspServerConnections
    
    val query = Messages.SelectBspServer.request(availableServers)
    for {
      item <- client.showMessageRequest(query.params).asScala
    } yield {
      val chosenMaybe = Option(item).flatMap(i => query.details.get(i.getTitle))
      val result = chosenMaybe.map { chosen =>
        if (chosen == bloop) {
          ResolveBloop
        } else {
         ResolveBspOne(chosen)
        }
      }.getOrElse(ResolveNone)
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
