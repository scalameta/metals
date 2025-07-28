package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildToolSelector
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.VersionRecommendation
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.semver.SemVer
import scala.meta.io.AbsolutePath

class BuildToolProvider(
    buildTools: BuildTools,
    tables: Tables,
    folder: AbsolutePath,
    warnings: ProjectWarnings,
    languageClient: MetalsLanguageClient,
    preferredBuildServer: Future[Option[String]],
)(implicit ec: ExecutionContext) {
  private val buildToolSelector: BuildToolSelector = new BuildToolSelector(
    languageClient,
    tables,
    preferredBuildServer,
  )

  def buildTool: Option[BuildTool] =
    for {
      name <- tables.buildTool.selectedBuildTool()
      buildTool <- buildTools.current().find(_.executableName == name)
      if isCompatibleVersion(buildTool)
    } yield buildTool

  def optProjectRoot: Option[AbsolutePath] =
    buildTool.map(_.projectRoot).orElse(buildTools.bloopProject)

  private def isCompatibleVersion(buildTool: BuildTool): Boolean = {
    buildTool match {
      case buildTool: VersionRecommendation =>
        SemVer.isCompatibleVersion(
          buildTool.minimumVersion,
          buildTool.version,
        )
      case _ => true
    }
  }

  /**
   * Checks if the version of the build tool is compatible with the version that
   * metals expects and returns the current digest of the build tool.
   */
  private def verifyBuildTool(buildTool: BuildTool): BuildTool.Verified = {
    buildTool match {
      case buildTool: VersionRecommendation
          if !isCompatibleVersion(buildTool) =>
        BuildTool.IncompatibleVersion(buildTool)
      case _ =>
        buildTool.digestWithRetry(folder) match {
          case Some(digest) =>
            BuildTool.Found(buildTool, digest)
          case None => BuildTool.NoChecksum(buildTool, folder)
        }
    }
  }

  def supportedBuildTool(): Future[Option[BuildTool.Found]] = {
    buildTools.loadSupported() match {
      case Nil => {
        if (!buildTools.isAutoConnectable()) {
          warnings.noBuildTool()
        }
        Future(None)
      }
      case buildTools =>
        for {
          buildTool <- buildToolSelector.checkForChosenBuildTool(
            buildTools
          )
        } yield {
          buildTool.flatMap { bt =>
            verifyBuildTool(bt) match {
              case found: BuildTool.Found => Some(found)
              case warn @ BuildTool.IncompatibleVersion(buildTool) =>
                scribe.warn(warn.message)
                languageClient.showMessage(
                  Messages.IncompatibleBuildToolVersion.params(buildTool)
                )
                None
              case warn: BuildTool.NoChecksum =>
                scribe.warn(warn.message)
                None
            }
          }
        }
    }
  }

  def onNewBuildToolAdded(
      newBuildTool: BuildTool,
      currentBuildTool: BuildTool,
  ): Future[Boolean] =
    buildToolSelector.onNewBuildToolAdded(newBuildTool, currentBuildTool)
}
