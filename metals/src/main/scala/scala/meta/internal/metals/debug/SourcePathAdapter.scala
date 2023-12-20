package scala.meta.internal.metals.debug

import java.net.URI

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

private[debug] final class SourcePathAdapter(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    supportVirtualDocuments: Boolean,
) {
  // when virtual documents are supported there is no need to save jars on disk
  private val saveJarFileToDisk = !supportVirtualDocuments
  private val dependencies = workspace.resolve(Directories.dependencies)

  def toDapURI(sourcePath: AbsolutePath): Option[URI] = {
    if (
      !supportVirtualDocuments && sourcePath.toNIO.startsWith(
        dependencies.toNIO
      )
    ) {
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
    } else {
      Some(sourcePath.toURI)
    }
  }

  def toMetalsPath(sourcePath: AbsolutePath): Option[AbsolutePath] = try {
    Some(
      if (sourcePath.isJarFileSystem)
        if (saveJarFileToDisk) sourcePath.toFileOnDisk(workspace)
        else sourcePath
      else sourcePath
    )
  } catch {
    case e: Throwable =>
      scribe.error(s"Could not resolve $sourcePath", e)
      None
  }
}

private[debug] object SourcePathAdapter {
  def apply(
      buildTargets: BuildTargets,
      targets: Seq[BuildTargetIdentifier],
      supportVirtualDocuments: Boolean,
  ): SourcePathAdapter = {
    val workspace = buildTargets.workspaceDirectory(targets.head).get
    new SourcePathAdapter(
      workspace,
      buildTargets,
      supportVirtualDocuments,
    )
  }
}
