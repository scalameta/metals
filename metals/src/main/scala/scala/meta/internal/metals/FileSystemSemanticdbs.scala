package scala.meta.internal.metals

import java.nio.charset.Charset

import scala.meta.internal.metals.MetalsEnrichments.given
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
    mainWorkspace: AbsolutePath,
    fingerprints: Md5Fingerprints,
) extends Semanticdbs {

  override def textDocument(file: AbsolutePath): TextDocumentLookup = {
    if (
      (!file.toLanguage.isScala && !file.toLanguage.isJava) ||
      file.toNIO.getFileSystem != mainWorkspace.toNIO.getFileSystem
    ) {
      TextDocumentLookup.NotFound(file)
    } else {

      val paths = for {
        buildTarget <- buildTargets.inverseSources(file)
        workspace <- buildTargets.workspaceDirectory(buildTarget)
        targetroot <- {
          val javaRoot =
            if (file.toLanguage.isJava)
              buildTargets.javaTargetRoot(buildTarget)
            else None
          javaRoot orElse buildTargets.scalaTargetRoot(buildTarget)
        }
      } yield {
        (workspace, targetroot)
      }

      paths match {
        case Some((ws, targetroot)) =>
          Semanticdbs.loadTextDocument(
            file,
            ws,
            charset,
            fingerprints,
            semanticdbRelativePath =>
              findSemanticDb(semanticdbRelativePath, targetroot, file, ws),
            (warn: String) => scribe.warn(warn),
          )
        case None =>
          TextDocumentLookup.NotFound(file)
      }
    }
  }

  def findSemanticDb(
      semanticdbRelativePath: RelativePath,
      targetroot: AbsolutePath,
      file: AbsolutePath,
      workspace: AbsolutePath,
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
        alternativeRelativePath = SemanticdbClasspath.fromScalaOrJava(
          fullRelativePath
        )
        alternativeSemanticdbPath = targetroot.resolve(
          alternativeRelativePath
        )
        if alternativeSemanticdbPath.isFile
      } yield FoundSemanticDbPath(
        alternativeSemanticdbPath,
        Some(fullRelativePath),
      )
    }

  }

}
