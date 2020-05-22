package scala.meta.internal.metals

import scala.meta.internal.builds.BuildTools
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLogger.{silentInTests => logger}
import scala.meta.internal.metals.ScalaVersions._
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
    isCompiling: BuildTargetIdentifier => Boolean
) {
  def noSemanticdb(path: AbsolutePath): Unit = {
    def doesntWorkBecause =
      s"code navigation does not work for the file '$path' because"
    def buildMisconfiguration(): Unit = {
      statusBar.addMessage(
        MetalsStatusParams(
          s"${icons.alert}Build misconfiguration",
          command = ClientCommands.RunDoctor.id
        )
      )
    }
    val isReported: Option[Unit] = for {
      buildTarget <- buildTargets.inverseSources(path)
      info <- buildTargets.scalaTarget(buildTarget)
    } yield {
      if (!info.isSemanticdbEnabled) {
        if (isSupportedScalaVersion(info.scalaVersion)) {
          logger.error(
            s"$doesntWorkBecause the SemanticDB compiler plugin is not enabled for the build target ${info.displayName}."
          )
          buildMisconfiguration()
        } else {
          logger.error(
            s"$doesntWorkBecause the Scala version ${info.scalaVersion} is not supported. " +
              s"To fix this problem, change the Scala version to ${isLatestScalaVersion.mkString(" or ")}."
          )
          statusBar.addMessage(
            s"${icons.alert}Unsupported Scala ${info.scalaVersion}"
          )
        }
      } else {
        if (!info.isSourcerootDeclared) {
          val option = workspace.sourcerootOption
          logger.error(
            s"$doesntWorkBecause the build target ${info.displayName} is missing the compiler option $option. " +
              s"To fix this problems, update the build settings to include this compiler option."
          )
          buildMisconfiguration()
        } else if (isCompiling(buildTarget)) {
          val tryAgain = "Wait until compilation is finished and try again"
          logger.error(
            s"$doesntWorkBecause the build target ${info.displayName} is being compiled. $tryAgain."
          )
          statusBar.addMessage(icons.info + tryAgain)
        } else {
          val targetfile = info.classDirectory.toAbsolutePath
            .resolve(SemanticdbClasspath.fromScala(path.toRelative(workspace)))
          logger.error(
            s"$doesntWorkBecause the SemanticDB file '$targetfile' doesn't exist. " +
              s"There can be many reasons for this error. "
          )
        }
      }
    }
    isReported match {
      case Some(()) =>
      case None =>
        if (buildTools.isEmpty) {
          noBuildTool()
        } else {
          logger.warn(
            s"$doesntWorkBecause it doesn't belong to a build target."
          )
          statusBar.addMessage(s"${icons.alert}No build target")
        }
    }
  }

  def noBuildTool(): Unit = {
    val tools = buildTools.all
    if (tools.isEmpty) {
      scribe.warn(
        s"no build tool detected in workspace '$workspace'. " +
          s"The most common cause for this problem is that the editor was opened in the wrong working directory, " +
          s"for example if you use sbt then the workspace directory should contain build.sbt. "
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
        command = ClientCommands.ToggleLogs.id
      )
    )
  }
}
