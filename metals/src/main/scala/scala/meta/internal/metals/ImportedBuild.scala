package scala.meta.internal.metals

import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import ch.epfl.scala.bsp4j._

/**
 * Metadata that we import from the build tool via BSP.
 */
case class ImportedBuild(
    workspaceBuildTargets: WorkspaceBuildTargetsResult,
    scalacOptions: ScalacOptionsResult,
    javacOptions: JavacOptionsResult,
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
    val updatedJavacOptions = new JavacOptionsResult(
      (javacOptions.getItems.asScala ++ other.javacOptions.getItems.asScala).asJava
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
      updatedJavacOptions,
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
      new JavacOptionsResult(ju.Collections.emptyList()),
      new SourcesResult(ju.Collections.emptyList()),
      new DependencySourcesResult(ju.Collections.emptyList())
    )

  def fromConnection(
      conn: BuildServerConnection
  )(implicit ec: ExecutionContext): Future[ImportedBuild] =
    for {
      workspaceBuildTargets <- conn.workspaceBuildTargets()
      ids = workspaceBuildTargets.getTargets.map(_.getId)
      scalacOptions <- conn.buildTargetScalacOptions(
        new ScalacOptionsParams(ids)
      )
      javacOptions <- conn.buildTargetJavacOptions(
        new JavacOptionsParams(ids)
      )
      sources <- conn.buildTargetSources(new SourcesParams(ids))
      dependencySources <- conn.buildTargetDependencySources(
        new DependencySourcesParams(ids)
      )
    } yield {
      ImportedBuild(
        workspaceBuildTargets,
        scalacOptions,
        javacOptions,
        sources,
        dependencySources
      )
    }

  def fromList(data: Seq[ImportedBuild]): ImportedBuild =
    if (data.isEmpty) empty
    else if (data.lengthCompare(1) == 0) data.head
    else data.reduce(_ ++ _)
}
