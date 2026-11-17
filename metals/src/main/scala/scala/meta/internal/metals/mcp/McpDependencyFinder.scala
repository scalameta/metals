package scala.meta.internal.metals.mcp

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.mcp.McpDependencyFinder.FindDepKey
import scala.meta.internal.mtags.CoursierComplete
import scala.meta.io.AbsolutePath

final class McpDependencyFinder(
    workspace: AbsolutePath,
    scalaVersionSelector: ScalaVersionSelector,
) {
  def find(
      organization: Option[String],
      name: Option[String],
      version: Option[String],
      fileInFocus: Option[AbsolutePath],
  ): String = {
    val scalaVersion = fileInFocus match {
      case Some(path) => scalaVersionSelector.scalaVersionForPath(path)
      case None => scalaVersionSelector.fallbackScalaVersion()
    }
    val coursierComplete = new CoursierComplete(scalaVersion)
    val potentialDepStrings = McpDependencyFinder.candidates(
      organization = organization,
      name = name,
      version = version,
      scalaBinaryVersion =
        ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion),
      sbtVersion = SbtBuildTool.loadVersion(workspace),
    )

    val completions = for {
      (key, depString) <- potentialDepStrings.view
      message <-
        if (key == FindDepKey.version) {
          val (stable, preRelease) =
            coursierComplete.completeVersions(depString)
          Option.when(stable.nonEmpty || preRelease.nonEmpty)(
            McpMessages.FindDep
              .versionMessage(stable.headOption, preRelease)
          )
        } else {
          val completed = coursierComplete.complete(depString)
          Option.when(completed.nonEmpty)(
            McpMessages.FindDep
              .dependencyReturnMessage(key, completed.distinct)
          )
        }
    } yield message

    completions.headOption.getOrElse(McpMessages.FindDep.noCompletionsFound)
  }
}

object McpDependencyFinder {
  object FindDepKey {
    val version = "version"
    val name = "name"
    val organization = "organization"
  }

  /** Coursier queries to try in order, paired with the field they complete. */
  def candidates(
      organization: Option[String],
      name: Option[String],
      version: Option[String],
      scalaBinaryVersion: String,
      sbtVersion: Option[String],
  ): List[(String, String)] = {
    val artifactCandidates = (name: String) => {
      val sbt1Plugin = s"${name}_2.12_1.0"
      val sbt2Plugin = s"${name}_sbt2_3"
      val sbtPlugins =
        if (sbtVersion.exists(_.startsWith("2.")))
          List(sbt2Plugin, sbt1Plugin)
        else List(sbt1Plugin, sbt2Plugin)
      (s"${name}_$scalaBinaryVersion" :: sbtPlugins) :+ name
    }

    (organization, name, version) match {
      case (Some(org), Some(name), Some(version)) =>
        val versionQuery = if (version.contains("latest")) "" else version
        artifactCandidates(name).map(artifact =>
          FindDepKey.version -> s"$org:$artifact:$versionQuery"
        )
      case (Some(org), Some(name), None) =>
        artifactCandidates(name).map(artifact =>
          FindDepKey.version -> s"$org:$artifact:"
        ) :+ (FindDepKey.name -> s"$org:$name")
      case (Some(org), None, _) =>
        List(
          FindDepKey.name -> s"$org:",
          FindDepKey.organization -> s"$org",
        )
      case _ => List()
    }
  }
}
