package scala.meta.internal.metals.debug

import java.net.URI
import java.nio.file.Paths

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.URIMapper
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

private[debug] final class SourcePathAdapter(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    uriMapper: URIMapper,
    supportVirtualDocuments: Boolean,
) {
  // when virtual documents are supported there is no need to save jars on disk
  private val saveJarFileToDisk = !supportVirtualDocuments
  private val dependencies = workspace.resolve(Directories.dependencies)

  def toDapURI(sourceUri: String): Option[URI] = {
    val localUri =
      if (sourceUri.startsWith(URIMapper.parentURI))
        uriMapper.convertToLocal(sourceUri)
      else sourceUri
    val sourcePath = localUri.toAbsolutePath
    if (saveJarFileToDisk && sourcePath.toNIO.startsWith(dependencies.toNIO)) {
      // if sourcePath is a dependency source file
      // we retrieve the original source jar and we build the uri inside the source jar filesystem
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
    } else
      Some(sourcePath.toURI)
  }

  def toMetalsPathOrUri(sourcePathOrUri: String): Option[String] = try {
    val metalsUri = if (sourcePathOrUri.startsWith("jar:")) {
      if (saveJarFileToDisk) {
        val path = sourcePathOrUri.toAbsolutePath
        path.toFileOnDisk(workspace).toURI.toString
      } else
        uriMapper.convertToMetalsFS(sourcePathOrUri)
    } else if (sourcePathOrUri.startsWith("file:"))
      sourcePathOrUri
    else
      Paths.get(sourcePathOrUri).toUri().toString
    Some(metalsUri)
  } catch {
    case e: Throwable =>
      scribe.error(s"Could not resolve $sourcePathOrUri", e)
      None
  }
}

private[debug] object SourcePathAdapter {
  def apply(
      buildTargets: BuildTargets,
      uriMapper: URIMapper,
      targets: Seq[BuildTargetIdentifier],
      supportVirtualDocuments: Boolean,
  ): SourcePathAdapter = {
    val workspace = buildTargets.workspaceDirectory(targets.head).get
    new SourcePathAdapter(
      workspace,
      buildTargets,
      uriMapper,
      supportVirtualDocuments,
    )
  }
}
