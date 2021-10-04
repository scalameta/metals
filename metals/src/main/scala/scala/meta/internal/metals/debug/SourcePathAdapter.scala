package scala.meta.internal.metals.debug

import java.net.URI
import java.nio.file.Paths

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

private[debug] final class SourcePathAdapter(
    workspace: AbsolutePath,
    sources: Set[AbsolutePath],
    buildTargets: BuildTargets
) {
  private val dependencies = workspace.resolve(Directories.dependencies)
  def toDapURI(sourcePath: AbsolutePath): Option[URI] = {
    if (sources.contains(sourcePath)) Some(sourcePath.toURI)
    else {
      // if sourcePath is a dependency source file
      // we retrieve the original source jar and we build the uri innside the source jar filesystem
      for {
        dependencySource <- sourcePath.toRelativeInside(dependencies)
        dependencyFolder <- dependencySource.toNIO.iterator.asScala.headOption
        jarName = dependencyFolder.toString
        jarFile <- buildTargets.sourceJarFile(jarName)
        relativePath <- sourcePath.toRelativeInside(
          dependencies.resolve(jarName)
        )
      } yield FileIO.withJarFileSystem(jarFile, create = false)(root =>
        root.resolve(relativePath.toString).toURI
      )
    }
  }

  def toMetalsPath(sourcePath: String): Option[AbsolutePath] = {
    val sourceUri = URI.create(sourcePath)
    sourceUri.getScheme match {
      case "jar" =>
        Option(AbsolutePath(Paths.get(sourceUri)).toFileOnDisk(workspace))
      case "file" => Some(sourcePath.toAbsolutePath)
      case _ => None
    }
  }
}

private[debug] object SourcePathAdapter {
  def apply(
      buildTargets: BuildTargets,
      targets: Seq[BuildTargetIdentifier]
  ): SourcePathAdapter = {
    val workspace = buildTargets.workspaceDirectory(targets.head).get
    val sources =
      targets.flatMap(buildTargets.buildTargetTransitiveSources).toSet
    new SourcePathAdapter(workspace, sources, buildTargets)
  }
}
