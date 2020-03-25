package scala.meta.internal.metals

import ch.epfl.scala.bsp4j._

/**
 * Metadata that we import from the build tool via BSP.
 */
case class ImportedBuild(
    workspaceBuildTargets: WorkspaceBuildTargetsResult,
    scalacOptions: ScalacOptionsResult,
    sources: SourcesResult,
    dependencySources: DependencySourcesResult
)
