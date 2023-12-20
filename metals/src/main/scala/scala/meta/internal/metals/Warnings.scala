package scala.meta.internal.metals

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.metals.ScalaVersions._
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.logging.MetalsLogger.{silentInTests => logger}
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

/**
 * A helper to construct clear and actionable warning messages.
 */
final class Warnings(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    statusBar: StatusBar,
    icons: Icons,
    buildTools: BuildTools,
    isCompiling: BuildTargetIdentifier => Boolean,
) {
  def noSemanticdb(path: AbsolutePath): Unit = {
    def doesntWorkBecause =
      s"code navigation does not work for the file '$path' because"

    def reportSemanticDB(
        buildTarget: BuildTargetIdentifier,
        targetName: String,
    ): Unit = {
      def pluginNotEnabled(language: String) =
        s"$doesntWorkBecause the $language SemanticDB compiler plugin is not enabled for the build target $targetName."

      def missingCompilerOption(
          language: String,
          option: String,
      ) =
        s"$doesntWorkBecause the build target $targetName is missing the $language compiler option $option. " +
          "To fix this problems, update the build settings to include this compiler option."

      def buildMisconfiguration(): Unit = {
        statusBar.addMessage(
          MetalsStatusParams(
            s"${icons.alert}Build misconfiguration",
            command = ClientCommands.RunDoctor.id,
          )
        )
      }

      if (path.isJavaFilename)
        buildTargets.javaTarget(buildTarget) match {
          case None =>
            logger.error(pluginNotEnabled("Java"))
            buildMisconfiguration()
          case Some(target) =>
            if (!target.isSemanticdbEnabled) {
              logger.error(pluginNotEnabled("Java"))
              buildMisconfiguration()
            } else if (!target.isSourcerootDeclared) {
              val option = workspace.javaSourcerootOption
              logger.error(missingCompilerOption("Java", option))
              buildMisconfiguration()
            }
        }
      else
        buildTargets.scalaTarget(buildTarget) match {
          case None =>
            logger.error(pluginNotEnabled("Scala"))
            buildMisconfiguration()
          case Some(target) =>
            if (!target.isSemanticdbEnabled) {
              if (isSupportedAtReleaseMomentScalaVersion(target.scalaVersion)) {
                logger.error(pluginNotEnabled("Scala"))
                buildMisconfiguration()
              } else {
                logger.error(
                  s"$doesntWorkBecause the Scala version ${target.scalaVersion} is not supported. " +
                    s"To fix this problem, change the Scala version to ${isLatestScalaVersion.mkString(" or ")}."
                )
                statusBar.addMessage(
                  s"${icons.alert}Unsupported Scala ${target.scalaVersion}"
                )
              }
            } else if (!target.isSourcerootDeclared) {
              val option = workspace.scalaSourcerootOption
              logger.error(missingCompilerOption("Scala", option))
              buildMisconfiguration()
            } else if (!path.isSbt && !path.isWorksheet) {
              val targetRoot = target.targetroot
              val targetfile = targetRoot
                .resolve(
                  SemanticdbClasspath.fromScalaOrJava(
                    path.toRelative(workspace)
                  )
                )
              logger.error(
                s"$doesntWorkBecause the SemanticDB file '$targetfile' doesn't exist. " +
                  s"There can be many reasons for this error. "
              )
            }
        }
    }

    if (buildTools.isEmpty)
      noBuildTool()
    else
      buildTargets.inverseSources(path) match {
        case None => {
          logger.warn(
            s"$doesntWorkBecause it doesn't belong to a build target."
          )
          statusBar.addMessage(s"${icons.alert}No build target")
        }
        case Some(buildTarget) =>
          val targetName = buildTargets
            .info(buildTarget)
            .map(_.getDisplayName())
            .getOrElse("Unknown")
          if (isCompiling(buildTarget)) {
            val tryAgain = "Wait until compilation is finished and try again"
            logger.error(
              s"$doesntWorkBecause the build target $targetName is being compiled. $tryAgain."
            )
            statusBar.addMessage(icons.info + tryAgain)
          } else reportSemanticDB(buildTarget, targetName)
      }
  }

  def noBuildTool(): Unit = {
    val tools = buildTools.all
    if (tools.isEmpty) {
      scribe.warn(
        s"no build tool detected in workspace '$workspace'. " +
          "The most common cause for this problem is that the editor was opened in the wrong working directory, " +
          "for example if you use sbt then the workspace directory should contain build.sbt. "
      )
    } else {
      val what =
        if (tools.length == 1) {
          s"build tool ${tools.head} is"
        } else {
          s"build tools ${tools.mkString(", ")} are"
        }
      scribe.warn(
        s"the $what not supported by Metals, please open an issue if you would like to contribute to improve the situation."
      )
    }
    statusBar.addMessage(
      MetalsStatusParams(
        s"${icons.alert}No build tool",
        command = ClientCommands.ToggleLogs.id,
      )
    )
  }
}
