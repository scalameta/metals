package scala.meta.internal.metals

import java.nio.charset.Charset

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.SemanticdbClasspath
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Semanticdbs.FoundSemanticDbPath
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

/**
 * Reads SemanticDBs from disk that are produces by the semanticdb-scalac compiler plugin.
 */
final class FileSystemSemanticdbs(
    buildTargets: BuildTargets,
    charset: Charset,
    workspace: AbsolutePath,
    fingerprints: Md5Fingerprints
) extends Semanticdbs {

  override def textDocument(file: AbsolutePath): TextDocumentLookup = {
    if (
      !file.toLanguage.isScala ||
      file.toNIO.getFileSystem != workspace.toNIO.getFileSystem
    ) {
      TextDocumentLookup.NotFound(file)
    } else {
      semanticdbTargetroot(file) match {
        case Some(targetroot) =>
          Semanticdbs.loadTextDocument(
            file,
            workspace,
            charset,
            fingerprints,
            semanticdbRelativePath =>
              findSemanticDb(semanticdbRelativePath, targetroot, file)
          )
        case None =>
          TextDocumentLookup.NotFound(file)
      }
    }
  }

  private def findSemanticDb(
      semanticdbRelativePath: RelativePath,
      targetroot: AbsolutePath,
      file: AbsolutePath
  ): Option[FoundSemanticDbPath] = {
    val semanticdbpath = targetroot.resolve(semanticdbRelativePath)
    if (semanticdbpath.isFile) Some(FoundSemanticDbPath(semanticdbpath, None))
    else {
      // needed in case sources are symlinked,
      for {
        sourceRoot <- buildTargets.originalInverseSourceItem(file)
        relativeSourceRoot = sourceRoot.toRelative(workspace)
        relativeFile = file.toRelative(sourceRoot.dealias)
        fullRelativePath = relativeSourceRoot.resolve(relativeFile)
        alternativeRelativePath = SemanticdbClasspath.fromScala(
          fullRelativePath
        )
        alternativeSemanticdbPath = targetroot.resolve(
          alternativeRelativePath
        )
        if alternativeSemanticdbPath.isFile
      } yield FoundSemanticDbPath(
        alternativeSemanticdbPath,
        Some(fullRelativePath)
      )
    }

  }

  /**
   * Returns the directory containing SemanticDB files for this Scala source file.
   */
  private def semanticdbTargetroot(
      scalaPath: AbsolutePath
  ): Option[AbsolutePath] = {
    for {
      buildTarget <- buildTargets.inverseSources(scalaPath)
      scalacOptions <- buildTargets.scalacOptions(buildTarget)
    } yield scalacOptions.targetroot
  }
}
