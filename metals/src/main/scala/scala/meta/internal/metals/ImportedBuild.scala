package scala.meta.internal.metals

import java.{util => ju}

import ch.epfl.scala.bsp4j._

import scala.collection.JavaConverters._

/**
 * Metadata that we import from the build tool via BSP.
 */
case class ImportedBuild(
    workspaceBuildTargets: WorkspaceBuildTargetsResult,
    scalacOptions: ScalacOptionsResult,
    sources: SourcesResult,
    dependencySources: DependencySourcesResult
) {
  def ++(other: ImportedBuild): ImportedBuild = {
    val updatedBuildTargets = new WorkspaceBuildTargetsResult(
      (workspaceBuildTargets.getTargets.asScala ++ other.workspaceBuildTargets.getTargets.asScala).asJava
    )
    val updatedScalacOptions = new ScalacOptionsResult(
      (scalacOptions.getItems.asScala ++ other.scalacOptions.getItems.asScala).asJava
    )
    val updatedSources = new SourcesResult(
      (sources.getItems.asScala ++ other.sources.getItems.asScala).asJava
    )
    val updatedDependencySources = new DependencySourcesResult(
      (dependencySources.getItems.asScala ++ other.dependencySources.getItems.asScala).asJava
    )
    ImportedBuild(
      updatedBuildTargets,
      updatedScalacOptions,
      updatedSources,
      updatedDependencySources
    )
  }

  lazy val targetUris: Set[String] =
    workspaceBuildTargets.getTargets.asScala
      .map(_.getId.getUri)
      .toSet
}

object ImportedBuild {
  def empty: ImportedBuild =
    ImportedBuild(
      new WorkspaceBuildTargetsResult(ju.Collections.emptyList()),
      new ScalacOptionsResult(ju.Collections.emptyList()),
      new SourcesResult(ju.Collections.emptyList()),
      new DependencySourcesResult(ju.Collections.emptyList())
    )
}
