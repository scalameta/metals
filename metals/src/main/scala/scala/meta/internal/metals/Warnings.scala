package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersions._
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.io.AbsolutePath

/**
 * A helper to construct clear and actionable warning messages.
 */
final class Warnings(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    statusBar: StatusBar,
    icons: Icons,
    isCompiling: BuildTargetIdentifier => Boolean
) {
  def noSemanticdb(path: AbsolutePath): Unit = {
    def doesntWorkBecause =
      s"no navigation: code navigation does not work for the file '$path' because"
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
      info <- buildTargets.info(buildTarget)
      scala <- info.asScalaBuildTarget
      scalacOptions <- buildTargets.scalacOptions(buildTarget)
    } yield {
      if (!scalacOptions.isSemanticdbEnabled) {
        if (isSupportedScalaVersion(scala.getScalaVersion)) {
          scribe.error(
            s"$doesntWorkBecause the SemanticDB compiler plugin is not enabled for the build target ${info.getDisplayName}."
          )
          buildMisconfiguration()
        } else {
          scribe.error(
            s"$doesntWorkBecause the Scala version ${scala.getScalaVersion} is not supported. " +
              s"To fix this problem, change the Scala version to ${isLatestScalaVersion.mkString(" or ")}."
          )
          statusBar.addMessage(
            s"${icons.alert}Unsupported Scala ${scala.getScalaVersion}"
          )
        }
      } else {
        if (!scalacOptions.isSourcerootDeclared) {
          val option = workspace.sourcerootOption
          scribe.error(
            s"$doesntWorkBecause the build target ${info.getDisplayName} is missing the compiler option $option. " +
              s"To fix this problems, update the build settings to include this compiler option."
          )
          buildMisconfiguration()
        } else if (isCompiling(buildTarget)) {
          val tryAgain = "Wait until compilation is finished and try again"
          scribe.error(
            s"$doesntWorkBecause the build target ${info.getDisplayName} is being compiled. $tryAgain."
          )
          statusBar.addMessage(icons.info + tryAgain)
        } else {
          val targetfile = scalacOptions.getClassDirectory.toAbsolutePath
            .resolve(SemanticdbClasspath.fromScala(path.toRelative(workspace)))
          scribe.error(
            s"$doesntWorkBecause the SemanticDB file '$targetfile' doesn't exist. " +
              s"There can be many reasons for this error. "
          )
          statusBar.addMessage(s"${icons.alert}No SemanticDB")
        }
      }
    }
    isReported match {
      case Some(()) =>
      case None =>
        scribe.warn(s"$doesntWorkBecause it doesn't belong to a build target.")
        statusBar.addMessage(s"${icons.alert}No build target")
    }
  }
}
