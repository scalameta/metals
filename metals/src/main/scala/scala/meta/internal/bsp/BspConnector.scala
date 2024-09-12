package scala.meta.internal.bsp

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.BuildServerConnection
import scala.meta.internal.metals.ConnectKind
import scala.meta.internal.metals.CreateSession
import scala.meta.internal.metals.GenerateBspConfigAndConnect
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages.BspSwitch
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.SlowConnect
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.scalacli.ScalaCli
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
    workDoneProgress: WorkDoneProgress,
    bspConfigGenerator: BspConfigGenerator,
    currentConnection: () => Option[BuildServerConnection],
    restartBspServer: () => Future[Unit],
    bspStatus: ConnectionBspStatus,
)(implicit ec: ExecutionContext) {

  /**
   * Resolves the current build servers that either have a bsp entry or if the
   * workspace can support Bloop, it will also resolve Bloop.
   */
  def resolve(buildTool: Option[BuildTool]): BspResolvedResult = {
    resolveExplicit().getOrElse {
      if (buildTool.exists(_.isBloopInstallProvider) || buildTools.isBloop)
        ResolvedBloop
      else bspServers.resolve()
    }
  }

  private def resolveExplicit(): Option[BspResolvedResult] = {
    tables.buildServers.selectedServer().flatMap { sel =>
      if (sel == BloopServers.name) Some(ResolvedBloop)
      else
        bspServers
          .findAvailableServers()
          .find(buildServer =>
            (ScalaCli.names(buildServer.getName()) && ScalaCli.names(sel)) ||
              buildServer.getName == sel
          )
          .map(ResolvedBspOne.apply)
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
      buildTool: Option[BuildTool],
      workspace: AbsolutePath,
      userConfiguration: () => UserConfiguration,
      shellRunner: ShellRunner,
  )(implicit ec: ExecutionContext): Future[Option[BspSession]] = {
    val projectRoot = buildTool.map(_.projectRoot).getOrElse(workspace)
    def connect(
        projectRoot: AbsolutePath,
        bspTraceRoot: AbsolutePath,
        addLivenessMonitor: Boolean,
        regeneratedConfig: Boolean = false,
    ): Future[Option[BuildServerConnection]] = {
      def bspStatusOpt = Option.when(addLivenessMonitor)(bspStatus)
      scribe.info("Attempting to connect to the build server...")
      resolve(buildTool) match {
        case ResolvedNone =>
          scribe.info("No build server found")
          Future.successful(None)
        case ResolvedBloop =>
          bloopServers
            .newServer(
              projectRoot,
              bspTraceRoot,
              userConfiguration,
              bspStatusOpt,
            )
            .map(Some(_))
        case ResolvedBspOne(details)
            if details.getName() == SbtBuildTool.name =>
          tables.buildServers.chooseServer(SbtBuildTool.name)
          val shouldReload = SbtBuildTool.writeSbtMetalsPlugins(projectRoot)
          def restartSbtBuildServer() = currentConnection()
            .withFilter(_.isSbt)
            .map(_ => restartBspServer())
            .getOrElse(Future.successful(()))
          val connectionF =
            for {
              _ <- SbtBuildTool(projectRoot, userConfiguration)
                .ensureCorrectJavaVersion(
                  shellRunner,
                  projectRoot,
                  client,
                  restartSbtBuildServer,
                )
              connection <- bspServers.newServer(
                projectRoot,
                bspTraceRoot,
                details,
                bspStatusOpt,
              )
              _ <-
                if (shouldReload) connection.workspaceReload()
                else Future.successful(())
            } yield connection
          workDoneProgress
            .trackFuture("Connecting to sbt", connectionF)
            .map(Some(_))
        case ResolvedBspOne(details) =>
          tables.buildServers.chooseServer(details.getName())
          optSetBuildTool(details.getName())
          buildTool match {
            case Some(bsp: BuildServerProvider)
                if bsp.shouldRegenerateBspJson(
                  details.getVersion()
                ) && !regeneratedConfig =>
              scribe.info(
                s"Regenerating ${details.getName()} json config to latest."
              )
              bsp
                .generateBspConfig(
                  workspace,
                  args => bspConfigGenerator.runUnconditionally(bsp, args),
                  statusBar,
                )
                .flatMap { _ =>
                  connect(
                    projectRoot,
                    bspTraceRoot,
                    addLivenessMonitor,
                    regeneratedConfig = true,
                  )
                }
            case _ =>
              bspServers
                .newServer(projectRoot, bspTraceRoot, details, bspStatusOpt)
                .map(Some(_))
          }

        case ResolvedMultiple(_, availableServers) =>
          val distinctServers = availableServers
            .groupBy(_.getName())
            .view
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
              None,
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
            _ = tables.buildServers.chooseServer(item.getName())
            _ = optSetBuildTool(item.getName())
            conn <- bspServers.newServer(
              projectRoot,
              bspTraceRoot,
              item,
              bspStatusOpt,
            )
          } yield Some(conn)
      }
    }

    connect(projectRoot, workspace, addLivenessMonitor = true).flatMap {
      possibleBuildServerConn =>
        possibleBuildServerConn match {
          case None => Future.successful(None)
          case Some(buildServerConn)
              if buildServerConn.isBloop && buildTool.exists {
                case _: SbtBuildTool => true
                case _ => false
              } =>
            // NOTE: (ckipp01) we special case this here since sbt bsp server
            // doesn't yet support metabuilds. So in the future when that
            // changes, re-work this and move the creation of this out above
            val metaConns = sbtMetaWorkspaces(workspace).map(root =>
              connect(root, root, addLivenessMonitor = false)
            )
            Future
              .sequence(metaConns)
              .map(meta => Some(BspSession(buildServerConn, meta.flatten)))
          case Some(buildServerConn) =>
            Future(Some(BspSession(buildServerConn, List.empty)))
        }
    }
  }

  /**
   * Looks for a build tool matching the chosen build server, and sets it as the chosen build server.
   * Only for `bloop` there will be no matching build tool and the previously chosen one remains.
   */
  private def optSetBuildTool(buildServerName: String): Unit =
    buildTools
      .loadSupported()
      .find {
        case _: ScalaCliBuildTool if ScalaCli.names(buildServerName) => true
        case buildTool => buildTool.buildServerName == buildServerName
      }
      .foreach(buildTool =>
        tables.buildTool.chooseBuildTool(buildTool.executableName)
      )

  private def sbtMetaWorkspaces(root: AbsolutePath): List[AbsolutePath] = {
    def recursive(
        p: AbsolutePath,
        acc: List[AbsolutePath],
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
      currentSelectedServer: Option[String],
  ): Future[Option[String]] = {
    val params = Messages.BspSwitch.chooseServerRequest(
      possibleServers,
      currentSelectedServer,
    )

    for {
      item <- client.showMessageRequest(params.params).asScala
    } yield Option(item).map(_.getTitle()).map(params.mapping(_))
  }

  /**
   * Runs "Switch build server" command, returns true if build server choice
   * was changed.
   *
   * NOTE: that in most cases this doesn't actually change your build server
   * and connect to it, but stores that you want to change it unless you are
   * choosing Bloop, since in that case it's special cased and does start it.
   */
  def switchBuildServer[T](): Future[Option[ConnectKind]] = {

    val foundServers = bspServers.findAvailableServers()
    val bloopPresent: Boolean = buildTools.isBloop

    // These are buildTools in the workspace that can serve as a build servers
    // and don't already have a .bsp entry
    val possibleServers: Map[String, Either[
      BuildServerProvider,
      BspConnectionDetails,
    ]] = buildTools
      .loadSupported()
      .collect {
        case buildTool: BuildServerProvider
            if !foundServers
              .exists(details =>
                details.getName() == buildTool.buildServerName
              ) =>
          buildTool.buildServerName -> Left(buildTool)
      }
      .toMap

    // These are build servers that already have a .bsp entry plus bloop if
    // it's an option.
    val availableServers: Map[String, Either[
      BuildServerProvider,
      BspConnectionDetails,
    ]] = {
      if (
        bloopPresent || buildTools
          .loadSupported()
          .exists(_.isBloopInstallProvider)
      )
        new BspConnectionDetails(
          BloopServers.name,
          ImmutableList.of(),
          userConfig().currentBloopVersion,
          "",
          ImmutableList.of(),
        ) :: foundServers
      else foundServers
    }.map { details =>
      details.getName() -> Right(details)
    }.toMap

    val allPossibleServers = possibleServers ++ availableServers

    def handleServerChoice(
        possibleChoice: Option[String],
        currentSelectedServer: Option[String],
    ) = {
      possibleChoice match {
        case Some(choice) =>
          allPossibleServers(choice) match {
            case Left(buildTool) => Some(GenerateBspConfigAndConnect(buildTool))
            case Right(details) if details.getName == BloopServers.name =>
              tables.buildServers.chooseServer(details.getName)
              if (bloopPresent) Some(CreateSession())
              else Some(SlowConnect)
            case Right(details)
                if !currentSelectedServer.contains(details.getName) =>
              tables.buildServers.chooseServer(details.getName)
              Some(CreateSession())
            case _ => None
          }
        case _ => None
      }
    }

    allPossibleServers.keys.toList match {
      case Nil =>
        client.showMessage(BspSwitch.noInstalledServer)
        Future.successful(None)
      case singleServer :: Nil =>
        allPossibleServers(singleServer) match {
          case Left(buildTool) =>
            Future.successful(Some(GenerateBspConfigAndConnect(buildTool)))
          case Right(connectionDetails) =>
            client.showMessage(
              BspSwitch.onlyOneServer(name = connectionDetails.getName())
            )
            Future.successful(None)
        }
      case multipleServers =>
        val currentSelectedServer =
          tables.buildServers
            .selectedServer()
            .orElse(currentConnection().map(_.name))
        askUser(multipleServers, currentSelectedServer).map(choice =>
          handleServerChoice(choice, currentSelectedServer)
        )
    }
  }
}
