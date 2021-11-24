package scala.meta.internal.bsp

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
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
import scala.meta.internal.semver.SemVer
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
    statusBar: StatusBar,
    bspConfigGenerator: BspConfigGenerator
)(implicit ec: ExecutionContext) {

  /**
   * Resolves the current build servers that either have a bsp entry or if the
   * workspace can support Bloop, it will also resolve Bloop.
   */
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

  /**
   * Handles the connection to the build server. This assumes that all
   * information that it needs is already in place by either having a
   * workspace that can work with Bloop or a workspace that already has a bsp
   * entry. In the case that a user is switching build servers the generation
   * of the bsp entry has already happened at this point.
   */
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
          SbtBuildTool.writeSbtMetalsPlugins(workspace)
          val connectionF = bspServers.newServer(workspace, details)
          statusBar
            .trackFuture("Connecting to sbt", connectionF, showTimer = true)
            .map(Some(_))
        case ResolvedBspOne(details) =>
          bspServers.newServer(workspace, details).map(Some(_))
        case ResolvedMultiple(_, availableServers) =>
          val distinctServers = availableServers
            .groupBy(_.getName())
            .mapValues {
              case singleVersion :: Nil => singleVersion
              case multipleVersions =>
                multipleVersions.reduceLeft[BspConnectionDetails] {
                  case (a, b) =>
                    if (
                      SemVer.Version.fromString(a.getVersion()) > SemVer.Version
                        .fromString(b.getVersion())
                    ) a
                    else b
                }
            }

          val query =
            Messages.BspSwitch.chooseServerRequest(
              distinctServers.keySet.toList,
              None
            )
          for {
            Some(item) <- client
              .showMessageRequest(query.params)
              .asScala
              .map(item =>
                Option(item).map(item =>
                  distinctServers(query.mapping(item.getTitle))
                )
              )
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

  /**
   * Have the user choose what server they'd like to use out of a list of
   * possible servers that are available to them in the workspace.
   *
   * @param possibleServers This could be servers that already have a bsp
   * entry or just build tools that the user is using that can also be a build
   * server. This is why we're working with strings instead of
   * bspConnectionDetails.
   * @param currentBsp a possible current choice they've made to explicit use
   * this server in the past.
   * @return
   */
  private def askUser(
      possibleServers: List[String],
      currentSelectedServer: Option[String]
  ): Future[Option[String]] = {
    val params = Messages.BspSwitch.chooseServerRequest(
      possibleServers,
      currentSelectedServer
    )

    for {
      item <- client.showMessageRequest(params.params).asScala
    } yield Option(item).map(_.getTitle()).map(params.mapping(_))
  }

  /**
   * Runs "Switch build server" command, returns true if build server choice
   * was changed.
   *
   * NOTE: that in most cases this doesn't actaully change your build server
   * and connect to it, but stores that you want to chage it unless you are
   * choosing Bloop, since in that case it's special cased and does start it.
   */
  def switchBuildServer(
      workspace: AbsolutePath,
      createBloopAndConnect: () => Future[BuildChange]
  ): Future[Boolean] = {

    val foundServers = bspServers.findAvailableServers()
    val bloopPresent: Boolean = buildTools.isBloop

    // These are buildTools in the workspace that can serve as a build servers
    // and don't already have a .bsp entry
    val possibleServers: Map[String, Either[
      BuildTool with BuildServerProvider,
      BspConnectionDetails
    ]] = buildTools
      .loadSupported()
      .collect {
        case buildTool: BuildServerProvider
            if !foundServers
              .exists(details =>
                details.getName() == buildTool.executableName
              ) =>
          buildTool
      }
      .map { possible =>
        possible.executableName -> Left(possible)
      }
      .toMap

    // These are build servers that already have a .bsp entry plus bloop if
    // it's an option.
    val availableServers: Map[String, Either[
      BuildTool with BuildServerProvider,
      BspConnectionDetails
    ]] = {
      if (bloopPresent || buildTools.loadSupported().nonEmpty)
        new BspConnectionDetails(
          BloopServers.name,
          ImmutableList.of(),
          userConfig().currentBloopVersion,
          "",
          ImmutableList.of()
        ) :: foundServers
      else foundServers
    }.map { details =>
      details.getName() -> Right(details)
    }.toMap

    val allPossibleServers = possibleServers ++ availableServers

    /**
     * Handles showing the user what they need to know after an attempt to
     * generate a bsp config has happened.
     */
    def handleGenerationStatus(
        buildTool: BuildTool,
        status: BspConfigGenerationStatus
    ): Boolean = status match {
      case BspConfigGenerationStatus.Generated =>
        tables.buildServers.chooseServer(buildTool.executableName)
        true
      case Cancelled => false
      case Failed(exit) =>
        exit match {
          case Left(exitCode) =>
            scribe.error(
              s"Creation of .bsp/${buildTool.executableName} failed with exit code: $exitCode"
            )
            client.showMessage(
              Messages.BspProvider.genericUnableToCreateConfig
            )
          case Right(message) =>
            client.showMessage(
              Messages.BspProvider.unableToCreateConfigFromMessage(
                message
              )
            )
        }
        false
    }

    def handleServerChoice(
        possibleChoice: Option[String],
        currentSelectedServer: Option[String]
    ) = {
      possibleChoice match {
        case Some(choice) =>
          allPossibleServers(choice) match {
            case Left(buildTool) =>
              buildTool
                .generateBspConfig(
                  workspace,
                  args => bspConfigGenerator.runUnconditionally(buildTool, args)
                )
                .map(status => handleGenerationStatus(buildTool, status))
            case Right(connectionDetails)
                if connectionDetails.getName == BloopServers.name && currentSelectedServer
                  .contains(
                    BspConnector.BLOOP_SELECTED
                  ) =>
              Future.successful(false)
            case Right(details) if details.getName == BloopServers.name =>
              tables.buildServers.chooseServer(BspConnector.BLOOP_SELECTED)
              if (bloopPresent) {
                Future.successful(true)
              } else {
                createBloopAndConnect().ignoreValue
                Future.successful(false)
              }
            case Right(details)
                if !currentSelectedServer.contains(details.getName) =>
              tables.buildServers.chooseServer(details.getName)
              Future.successful(true)
          }
        case _ =>
          Future.successful(false)
      }
    }

    allPossibleServers.keys.toList match {
      case Nil =>
        client.showMessage(BspSwitch.noInstalledServer)
        Future.successful(false)
      case singleServer :: Nil =>
        allPossibleServers(singleServer) match {
          case Left(buildTool) =>
            buildTool
              .generateBspConfig(
                workspace,
                args => bspConfigGenerator.runUnconditionally(buildTool, args)
              )
              .map(status => handleGenerationStatus(buildTool, status))
          case Right(connectionDetails) =>
            client.showMessage(
              BspSwitch.onlyOneServer(name = connectionDetails.getName())
            )
            Future.successful(false)
        }

      case multipleServers =>
        val currentSelectedServer = tables.buildServers.selectedServer()
        askUser(multipleServers, currentSelectedServer).flatMap(choice =>
          handleServerChoice(choice, currentSelectedServer)
        )
    }
  }
}

object BspConnector {
  final val BLOOP_SELECTED = "BLOOP"
}
