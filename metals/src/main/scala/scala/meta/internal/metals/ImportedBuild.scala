package scala.meta.internal.metals

import java.{util => ju}

import scala.build.bsp.WrappedSourcesParams
import scala.build.bsp.WrappedSourcesResult
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
    dependencySources: DependencySourcesResult,
    wrappedSources: WrappedSourcesResult,
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
    val updatedWrappedSources = new WrappedSourcesResult(
      (wrappedSources.getItems.asScala ++ other.wrappedSources.getItems.asScala).asJava
    )
    ImportedBuild(
      updatedBuildTargets,
      updatedScalacOptions,
      updatedJavacOptions,
      updatedSources,
      updatedDependencySources,
      updatedWrappedSources,
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
      new DependencySourcesResult(ju.Collections.emptyList()),
      new WrappedSourcesResult(ju.Collections.emptyList()),
    )

  def fromConnection(
      conn: BuildServerConnection
  )(implicit ec: ExecutionContext): Future[ImportedBuild] =
    for {
      allBuildTargets <- conn.workspaceBuildTargets()
      workspaceBuildTargets = relevantBuildTargets(allBuildTargets)
      ids = workspaceBuildTargets.getTargets.map(_.getId)
      scalacOptions <- conn.buildTargetScalacOptions(
        new ScalacOptionsParams(ids)
      )
      javacOptions <- conn.buildTargetJavacOptions(
        new JavacOptionsParams(ids)
      )
      sources <- conn.buildTargetSources(new SourcesParams(ids))
      bspProvidedDependencySources <- conn.buildTargetDependencySources(
        new DependencySourcesParams(ids)
      )
      dependencySources <- resolveMissingDependencySources(
        bspProvidedDependencySources,
        javacOptions,
        scalacOptions,
      )
      wrappedSources <- conn.buildTargetWrappedSources(
        new WrappedSourcesParams(ids)
      )
    } yield {
      ImportedBuild(
        workspaceBuildTargets,
        scalacOptions,
        javacOptions,
        sources,
        dependencySources,
        wrappedSources,
      )
    }

  private def relevantBuildTargets(
      workspaceBuildTargets: WorkspaceBuildTargetsResult
  ): WorkspaceBuildTargetsResult = {
    val targets = workspaceBuildTargets.getTargets.asScala.toList
    val scalaJavaTargets = targets.filter { target =>
      val langIds = target.getLanguageIds.asScala.toList
      langIds.contains("scala") || langIds.contains("java")
    }
    workspaceBuildTargets.setTargets(scalaJavaTargets.asJava)
    workspaceBuildTargets
  }

  private def resolveMissingDependencySources(
      dependencySources: DependencySourcesResult,
      javacOptions: JavacOptionsResult,
      scalacOptions: ScalacOptionsResult,
  )(implicit ec: ExecutionContext): Future[DependencySourcesResult] = {
    val dependencySourcesItems = dependencySources.getItems().asScala.toList
    val idsLookup = dependencySourcesItems.map(_.getTarget()).toSet
    val classpaths = javacOptions
      .getItems()
      .asScala
      .map(item => (item.getTarget(), item.getClasspath())) ++
      scalacOptions
        .getItems()
        .asScala
        .map(item => (item.getTarget(), item.getClasspath()))

    val newItemsFuture =
      Future.sequence {
        classpaths.collect {
          case (id, classpath) if !idsLookup(id) =>
            for {
              items <- JarSourcesProvider.fetchSources(
                classpath.asScala.filter(_.endsWith(".jar")).toSeq
              )
            } yield new DependencySourcesItem(id, items.asJava)
        }
      }

    newItemsFuture.map { newItems =>
      new DependencySourcesResult((dependencySourcesItems ++ newItems).asJava)
    }
  }

  def fromList(data: Seq[ImportedBuild]): ImportedBuild =
    if (data.isEmpty) empty
    else if (data.lengthCompare(1) == 0) data.head
    else data.reduce(_ ++ _)
}
